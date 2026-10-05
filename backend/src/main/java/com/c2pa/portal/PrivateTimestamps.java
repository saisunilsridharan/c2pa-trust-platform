package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
@Service public class PrivateTimestamps {
 public record Configuration(boolean enabled,String endpoint,String bearerCredential,String tlsCaPem,String tsaAnchorsPem,boolean allowLoopbackHttp){}
 public record Snapshot(String versionId,Configuration configuration){}
 private final TimestampSettingsRepository settings;private final TimestampVersionRepository versions;private final CredentialService credentials;private final TrustPolicy trust;private final com.fasterxml.jackson.databind.ObjectMapper mapper;
 public PrivateTimestamps(TimestampSettingsRepository settings,TimestampVersionRepository versions,CredentialService credentials,TrustPolicy trust,com.fasterxml.jackson.databind.ObjectMapper mapper){this.settings=settings;this.versions=versions;this.credentials=credentials;this.trust=trust;this.mapper=mapper;}
 public Configuration decode(String value)throws Exception{return mapper.readValue(value,Configuration.class);}
 public Configuration validate(Long workspace,Configuration c)throws Exception{
  if(c==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);if(!c.enabled())return new Configuration(false,null,null,null,null,false);
  try{
   if(c.endpoint()==null || c.endpoint().length()>2000 || c.tlsCaPem()!=null && c.tlsCaPem().length()>12000)throw new IllegalArgumentException();
   URI uri=URI.create(c.endpoint());if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();ServiceEndpoint.validate(new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toString(),c.allowLoopbackHttp());PrivateObjectStorage.trust(c.tlsCaPem());trust.validate(new TrustPolicy.Configuration(c.tsaAnchorsPem(),true));
   if(c.bearerCredential()!=null && !c.bearerCredential().isBlank()){byte[] secret=credentials.read(workspace,c.bearerCredential());try{if(secret.length<1 || secret.length>4096 || !new String(secret,java.nio.charset.StandardCharsets.US_ASCII).chars().allMatch(ch->ch>=33 && ch<=126))throw new IllegalArgumentException();}finally{Arrays.fill(secret,(byte)0);}}
   return c;
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid private TSA endpoint, CA anchors, TLS or workspace authentication credential");}
 }
 public String snapshot(Long workspace)throws Exception{var id=settings.findById(workspace).map(s->s.activeVersion).orElse(null);if(id==null)return null;var v=versions.findById(id).filter(x->x.workspaceId.equals(workspace)).orElseThrow();var configuration=decode(v.configuration);return configuration.enabled()?mapper.writeValueAsString(new Snapshot(v.id,configuration)):null;}
 public byte[] submit(Long workspace,String snapshot,byte[] body)throws Exception{
  var selected=mapper.readValue(snapshot,Snapshot.class);var c=validate(workspace,selected.configuration());if(!c.enabled() || body.length<1 || body.length>65536)throw new IllegalArgumentException();
  var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);var managers=PrivateObjectStorage.trust(c.tlsCaPem());if(managers!=null){var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(null,managers,null);builder.sslContext(ssl);}
  var request=HttpRequest.newBuilder(URI.create(c.endpoint())).timeout(Duration.ofSeconds(15)).header("Content-Type","application/timestamp-query").header("Accept","application/timestamp-reply").POST(HttpRequest.BodyPublishers.ofByteArray(body));
  if(c.bearerCredential()!=null && !c.bearerCredential().isBlank()){byte[] secret=credentials.read(workspace,c.bearerCredential());try{request.header("Authorization","Bearer "+new String(secret,java.nio.charset.StandardCharsets.US_ASCII));}finally{Arrays.fill(secret,(byte)0);}}
  try(var client=builder.build()){var response=client.send(request.build(),info->new BoundedHttpBody(65536));if(response.statusCode()!=200 || !response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim().equalsIgnoreCase("application/timestamp-reply") || response.body().length==0)throw new IllegalStateException();var timestamp=new org.bouncycastle.tsp.TimeStampResponse(response.body());timestamp.validate(new org.bouncycastle.tsp.TimeStampRequest(body));if(timestamp.getTimeStampToken()==null)throw new IllegalStateException();return response.body();}
 }
}
