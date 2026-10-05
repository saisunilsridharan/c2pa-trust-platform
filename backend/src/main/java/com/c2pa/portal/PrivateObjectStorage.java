package com.c2pa.portal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
@Service
public class PrivateObjectStorage {
 public record Configuration(String provider,String endpoint,String region,String bucket,String prefix,String accessKeyCredential,String secretKeyCredential,boolean allowLoopbackHttp,String trustedCaPem){}
 private final CredentialService credentials;private final ObjectMapper mapper;
 public PrivateObjectStorage(CredentialService credentials,ObjectMapper mapper){this.credentials=credentials;this.mapper=mapper;}
 public Configuration validate(Long workspace,Configuration settings)throws Exception {
  if(settings==null || !Set.of("LOCAL","S3").contains(settings.provider()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select local or S3-compatible storage");
  if(settings.provider().equals("LOCAL"))return new Configuration("LOCAL",null,null,null,null,null,null,false,null);
  if(settings.region()==null || !settings.region().matches("[a-zA-Z0-9-]{1,40}") || settings.bucket()==null || !settings.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || settings.prefix()==null || !settings.prefix().matches("[a-zA-Z0-9_-]{1,80}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid region, bucket or object prefix");
  if(settings.trustedCaPem()!=null && settings.trustedCaPem().length()>12000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CA bundle too large");
  ServiceEndpoint.validate(settings.endpoint(),settings.allowLoopbackHttp());
  for(String id:List.of(Objects.requireNonNullElse(settings.accessKeyCredential(),""),Objects.requireNonNullElse(settings.secretKeyCredential(),""))){byte[] secret=credentials.read(workspace,id);Arrays.fill(secret,(byte)0);}
  return settings;
 }
 private javax.net.ssl.TrustManager[] trust(String pem)throws Exception {
  if(pem==null || pem.isBlank())return null;
  var certificates=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
  if(certificates.isEmpty())throw new IllegalArgumentException("Empty CA bundle");
  var store=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());store.load(null);
  int n=0;for(var certificate:certificates){var x509=(java.security.cert.X509Certificate)certificate;x509.checkValidity();if(x509.getBasicConstraints()<0)throw new IllegalArgumentException("A CA certificate is required");store.setCertificateEntry("private-ca-"+(n++),certificate);}
  var factory=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());factory.init(store);return factory.getTrustManagers();
 }
 private S3Client client(Long workspace,Configuration settings)throws Exception {
  var endpoint=ServiceEndpoint.validate(settings.endpoint(),settings.allowLoopbackHttp());
  byte[] access=credentials.read(workspace,settings.accessKeyCredential()),secret=null;
  try {
   secret=credentials.read(workspace,settings.secretKeyCredential());
   var http=UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30));
   var managers=trust(settings.trustedCaPem());if(managers!=null)http.tlsTrustManagersProvider(()->managers);
   return S3Client.builder().endpointOverride(endpoint).region(Region.of(settings.region())).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(new String(access,StandardCharsets.UTF_8),new String(secret,StandardCharsets.UTF_8)))).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).httpClientBuilder(http).overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(40)).apiCallAttemptTimeout(Duration.ofSeconds(35))).build();
  }finally{Arrays.fill(access,(byte)0);if(secret!=null)Arrays.fill(secret,(byte)0);}
 }
 public void test(Long workspace,Configuration configuration)throws Exception {
  var settings=validate(workspace,configuration);if(settings.provider().equals("LOCAL"))return;
  try(var client=client(workspace,settings)){
   String key=settings.prefix()+"/workspace-"+workspace+"/connection-tests/"+UUID.randomUUID();byte[] challenge=new byte[32];new java.security.SecureRandom().nextBytes(challenge);
   try{client.putObject(PutObjectRequest.builder().bucket(settings.bucket()).key(key).contentType("application/octet-stream").build(),RequestBody.fromBytes(challenge));byte[] found=client.getObjectAsBytes(GetObjectRequest.builder().bucket(settings.bucket()).key(key).build()).asByteArray();if(!java.security.MessageDigest.isEqual(challenge,found))throw new IllegalStateException();}
   finally{client.deleteObject(DeleteObjectRequest.builder().bucket(settings.bucket()).key(key).build());}
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Storage test failed; check endpoint, TLS, bucket and read/write/delete credentials");}
 }
 public Configuration decode(String snapshot)throws Exception{return snapshot==null?null:mapper.readValue(snapshot,Configuration.class);}
 public boolean remote(SigningJob job)throws Exception{var c=decode(job.storageSnapshot);return c!=null && c.provider().equals("S3");}
 private String key(SigningJob job,Configuration c,String name){if(!Set.of("original"+ContentFormats.extension(job.format),"signed"+ContentFormats.extension(job.format),"report.json").contains(name))throw new IllegalArgumentException();return c.prefix()+"/workspace-"+job.workspaceId+"/jobs/"+job.id+"/"+name;}
 public void write(SigningJob job,String name,Path file)throws Exception{
  if(!remote(job))return;var c=decode(job.storageSnapshot);try(var client=client(job.workspaceId,c)){client.putObject(PutObjectRequest.builder().bucket(c.bucket()).key(key(job,c,name)).build(),RequestBody.fromFile(file));}
 }
 public byte[] read(SigningJob job,String name,long limit)throws Exception{
  var c=decode(job.storageSnapshot);try(var client=client(job.workspaceId,c)){
   var request=GetObjectRequest.builder().bucket(c.bucket()).key(key(job,c,name)).build();
   try(var input=client.getObject(request)){if(input.response().contentLength()>limit)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Asset exceeds download limit");byte[] bytes=input.readNBytes(Math.toIntExact(limit+1));if(bytes.length>limit)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Asset exceeds download limit");return bytes;}
  }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Private object storage is unavailable");}
 }
 public void delete(SigningJob job)throws Exception{
  if(!remote(job))return;var c=decode(job.storageSnapshot);try(var client=client(job.workspaceId,c)){for(String name:List.of("original"+ContentFormats.extension(job.format),"signed"+ContentFormats.extension(job.format),"report.json"))client.deleteObject(DeleteObjectRequest.builder().bucket(c.bucket()).key(key(job,c,name)).build());}
 }
}
