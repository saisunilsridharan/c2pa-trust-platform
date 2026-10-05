package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
@Service public class PrivateCa {
 public record Configuration(boolean enabled,String endpoint,String provisioner,String jwkCredential,String tlsCaPem,String issuanceAnchorsPem,int validityHours,boolean allowLoopbackHttp){}
 private final CredentialService credentials;private final TrustPolicy trust;private final HardwareSigning hardware;private final HardwareCertificateRequests requests;private final HardwareIdentitiesController identities;private final ObjectMapper mapper;private final AuditService audit;
 public PrivateCa(CredentialService credentials,TrustPolicy trust,HardwareSigning hardware,HardwareCertificateRequests requests,HardwareIdentitiesController identities,ObjectMapper mapper,AuditService audit){this.credentials=credentials;this.trust=trust;this.hardware=hardware;this.requests=requests;this.identities=identities;this.mapper=mapper;this.audit=audit;}
 public Configuration decode(String json)throws Exception{return mapper.readValue(json,Configuration.class);}
 private ECKey key(Long workspace,String id)throws Exception{
  byte[] bytes=credentials.read(workspace,id);
  try{if(bytes.length>16000)throw new IllegalArgumentException();JWK key=JWK.parse(new String(bytes,StandardCharsets.UTF_8));if(!(key instanceof ECKey ec) || !ec.isPrivate() || !ec.getCurve().equals(Curve.P_256) || ec.getKeyID()==null || ec.getKeyID().isBlank() || ec.getKeyID().length()>200 || ec.getKeyUse()!=null && !ec.getKeyUse().equals(KeyUse.SIGNATURE) || ec.getAlgorithm()!=null && !ec.getAlgorithm().equals(JWSAlgorithm.ES256) || ec.getKeyOperations()!=null && !ec.getKeyOperations().isEmpty() && !ec.getKeyOperations().contains(KeyOperation.SIGN))throw new IllegalArgumentException();return ec;}finally{Arrays.fill(bytes,(byte)0);}
 }
 public Configuration validate(Long workspace,Configuration c)throws Exception{
  if(c==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);if(!c.enabled())return new Configuration(false,null,null,null,null,null,24,false);
  try{if(c.endpoint()==null || c.endpoint().length()>2000 || c.provisioner()==null || c.provisioner().isBlank() || c.provisioner().length()>120 || c.validityHours()<1 || c.validityHours()>720 || c.tlsCaPem()!=null && c.tlsCaPem().length()>12000)throw new IllegalArgumentException();ServiceEndpoint.validate(c.endpoint(),c.allowLoopbackHttp());PrivateObjectStorage.trust(c.tlsCaPem());trust.validate(new TrustPolicy.Configuration(c.issuanceAnchorsPem(),true));key(workspace,c.jwkCredential());return c;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Configure a private CA endpoint, ES256 private provisioner JWK credential, TLS/issuance anchors and validity of 1–720 hours");}
 }
 private org.bouncycastle.pkcs.PKCS10CertificationRequest parse(byte[] csr)throws Exception{try(var reader=new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(new String(csr,StandardCharsets.US_ASCII)))){Object value=reader.readObject();if(!(value instanceof org.bouncycastle.pkcs.PKCS10CertificationRequest request))throw new IllegalArgumentException();return request;}}
 public String sign(Long workspace,Configuration configuration,byte[] csr,HardwareCertificateRequests.Subject subject)throws Exception{
  var c=validate(workspace,configuration);if(!c.enabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Private CA issuance is disabled");
  var request=parse(csr);Instant now=Instant.now();URI endpoint=ServiceEndpoint.validate(c.endpoint(),c.allowLoopbackHttp()).resolve("/sign");var key=key(workspace,c.jwkCredential());
  var claims=new JWTClaimsSet.Builder().issuer(c.provisioner()).subject(subject.commonName().trim()).audience(endpoint.toString()).issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(30))).expirationTime(Date.from(now.plusSeconds(120))).jwtID(UUID.randomUUID().toString()).claim("sans",List.of(subject.commonName().trim())).claim("cnf",Map.of("x5rt#S256",Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(request.getEncoded())))).build();
  var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),claims);token.sign(new ECDSASigner(key));
  var body=mapper.writeValueAsBytes(Map.of("csr",new String(csr,StandardCharsets.US_ASCII),"ott",token.serialize(),"notBefore",now.minusSeconds(30).toString(),"notAfter",now.plusSeconds(c.validityHours()*3600L).toString()));
  var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);var managers=PrivateObjectStorage.trust(c.tlsCaPem());if(managers!=null){var context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(null,managers,null);builder.sslContext(context);}
  try(var client=builder.build()){
   var response=client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),info->new BoundedHttpBody(100000));
   if(response.statusCode()!=201 && response.statusCode()!=200 || !response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim().equalsIgnoreCase("application/json"))throw new IllegalStateException();
   var result=mapper.readTree(response.body());StringBuilder pem=new StringBuilder();var supplied=result.path("certChain");if(supplied.isArray() && !supplied.isEmpty()){if(supplied.size()>10)throw new IllegalStateException();for(var certificate:supplied){if(!certificate.isTextual())throw new IllegalStateException();pem.append(certificate.asText()).append('\n');}}else{pem.append(result.path("crt").asText()).append('\n').append(result.path("ca").asText());}
   var chain=hardware.certificates(pem.toString());var leaf=chain.getFirst();var publicKey=request.getSubjectPublicKeyInfo().getEncoded();
   if(!MessageDigest.isEqual(leaf.getPublicKey().getEncoded(),publicKey) || !leaf.getSubjectX500Principal().equals(new javax.security.auth.x500.X500Principal(request.getSubject().getEncoded())) || leaf.getNotAfter().toInstant().isAfter(now.plusSeconds(c.validityHours()*3600L+300)))throw new IllegalStateException("Issued certificate does not match the reviewed request");
   var names=leaf.getSubjectAlternativeNames();if(names==null || names.size()!=1)throw new IllegalStateException("Issued SANs differ from the request");var name=names.iterator().next();if(!Integer.valueOf(2).equals(name.getFirst()) || !subject.commonName().trim().equalsIgnoreCase(String.valueOf(name.get(1))))throw new IllegalStateException("Issued SANs differ from the request");
   var anchors=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(c.issuanceAnchorsPem().getBytes(StandardCharsets.US_ASCII))).stream().map(cert->new TrustAnchor((X509Certificate)cert,null)).collect(java.util.stream.Collectors.toSet());
   List<X509Certificate> path=new ArrayList<>(chain);if(anchors.stream().anyMatch(anchor->anchor.getTrustedCert().equals(path.getLast())))path.removeLast();var parameters=new PKIXParameters(anchors);parameters.setRevocationEnabled(false);CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(path),parameters);
   return pem.toString();
  }finally{Arrays.fill(body,(byte)0);}
 }
 public HardwareIdentitiesController.View issue(Long workspace,String version,Configuration c,String identityId,String fingerprint,HardwareCertificateRequests.Subject subject)throws Exception{
  var identity=hardware.get(identityId,workspace);if(!Objects.equals(identity.fingerprint,fingerprint))throw new ResponseStatusException(HttpStatus.CONFLICT,"Review the selected certificate again");
  byte[] csr=requests.csr(identity,subject,true);audit.record(workspace,"PRIVATE_CA_CERTIFICATE_REQUESTED",version+":"+identity.id);
  String chain;try{chain=sign(workspace,c,csr,subject);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Private CA issuance failed; check provisioner permissions, C2PA certificate template, TLS, anchors and requested subject/validity");}finally{Arrays.fill(csr,(byte)0);}
  var renewed=identities.renew(identity.id,new HardwareIdentitiesController.Renewal(fingerprint,chain,true));audit.record(workspace,"PRIVATE_CA_CERTIFICATE_ISSUED",version+":"+renewed.id());return renewed;
 }
}
