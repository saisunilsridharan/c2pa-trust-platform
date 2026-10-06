package com.c2pa.portal;

import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cert.ocsp.*;
import org.bouncycastle.operator.jcajce.*;

/** Explicit administrator endpoint; never follows certificate-provided URLs. */
public final class OnlineOcsp {
 public record Configuration(String endpoint,String tlsCaPem,boolean allowLoopbackHttp){}
 private static final Set<String> SIGNATURES=Set.of("1.2.840.113549.1.1.11","1.2.840.113549.1.1.12","1.2.840.113549.1.1.13","1.2.840.10045.4.3.2","1.2.840.10045.4.3.3","1.2.840.10045.4.3.4","1.3.101.112","1.3.101.113");
 private OnlineOcsp(){}
 public static void validate(Configuration c)throws Exception{
  if(c==null)return;
  if(c.endpoint()==null || c.endpoint().length()>2000 || c.tlsCaPem()!=null && c.tlsCaPem().length()>12000)throw new IllegalArgumentException();
  URI uri=URI.create(c.endpoint());
  if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();
  ServiceEndpoint.validate(new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toString(),c.allowLoopbackHttp());
  PrivateObjectStorage.trust(c.tlsCaPem());
 }
 public static void check(Configuration c,X509Certificate certificate,X509Certificate issuer,Collection<java.security.cert.X509CRL> crls)throws Exception{
  validate(c);
  var digests=new JcaDigestCalculatorProviderBuilder().build();
  // SHA-1 here identifies an issuer; response signatures require a strong algorithm.
  var id=new CertificateID(digests.get(CertificateID.HASH_SHA1),new JcaX509CertificateHolder(issuer),certificate.getSerialNumber());
  byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
  var builder=new OCSPReqBuilder();builder.addRequest(id);
  builder.setRequestExtensions(new Extensions(new Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce,false,new DEROctetString(nonce).getEncoded())));
  var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
  var managers=PrivateObjectStorage.trust(c.tlsCaPem());if(managers!=null){var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(null,managers,null);http.sslContext(ssl);}
  var request=HttpRequest.newBuilder(URI.create(c.endpoint())).timeout(Duration.ofSeconds(10)).header("Content-Type","application/ocsp-request").header("Accept","application/ocsp-response").POST(HttpRequest.BodyPublishers.ofByteArray(builder.build().getEncoded())).build();
  byte[] body;
  try(var client=http.build()){
   var response=client.send(request,info->new BoundedHttpBody(65536));
   if(response.statusCode()!=200 || !response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim().equalsIgnoreCase("application/ocsp-response"))throw new IllegalStateException("OCSP response unavailable");
   body=response.body();
  }
  verify(body,id,nonce,issuer,crls);
 }
 static void verify(byte[] body,CertificateID id,byte[] nonce,X509Certificate issuer,Collection<java.security.cert.X509CRL> crls)throws Exception{
  var response=new OCSPResp(body);
  if(response.getStatus()!=OCSPResp.SUCCESSFUL || !(response.getResponseObject() instanceof BasicOCSPResp basic))throw new IllegalArgumentException("OCSP unsuccessful");
  if(!SIGNATURES.contains(basic.getSignatureAlgOID().getId()))throw new IllegalArgumentException("OCSP weak signature");
  Instant now=Instant.now();
  if(basic.getProducedAt()==null || basic.getProducedAt().toInstant().isAfter(now.plusSeconds(60)) || basic.getProducedAt().toInstant().isBefore(now.minusSeconds(600)))throw new IllegalArgumentException("OCSP response not fresh");
  var extension=basic.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
  if(extension==null || extension.isCritical() || !MessageDigest.isEqual(nonce,ASN1OctetString.getInstance(extension.getParsedValue()).getOctets()))throw new IllegalArgumentException("OCSP nonce mismatch");
  if(basic.getCriticalExtensionOIDs()!=null && !basic.getCriticalExtensionOIDs().isEmpty())throw new IllegalArgumentException("Unsupported OCSP extension");
  X509Certificate signer=null;
  var candidates=new ArrayList<X509Certificate>();candidates.add(issuer);
  if(basic.getCerts().length>5)throw new IllegalArgumentException();
  for(var holder:basic.getCerts())candidates.add(new JcaX509CertificateConverter().getCertificate(holder));
  var digests=new JcaDigestCalculatorProviderBuilder().build();
  for(var candidate:candidates){
   try{
    candidate.checkValidity();
    var name=new RespID(new JcaX509CertificateHolder(candidate).getSubject());
    var key=new RespID(new JcaX509CertificateHolder(candidate).getSubjectPublicKeyInfo(),digests.get(RespID.HASH_SHA1));
    if(!basic.getResponderId().equals(name) && !basic.getResponderId().equals(key))continue;
    if(!basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(candidate.getPublicKey())))continue;
    if(!Arrays.equals(candidate.getEncoded(),issuer.getEncoded())){
     if(!candidate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))continue;
     candidate.verify(issuer.getPublicKey());
     if(!SIGNATURES.contains(candidate.getSigAlgOID()) || candidate.hasUnsupportedCriticalExtension() || candidate.getBasicConstraints()>=0 || candidate.getExtendedKeyUsage()==null || !candidate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.9") || candidate.getKeyUsage()==null || candidate.getKeyUsage().length<1 || !candidate.getKeyUsage()[0])continue;
     var publicKey=candidate.getPublicKey();
     if(!(publicKey instanceof java.security.interfaces.RSAPublicKey rsa && rsa.getModulus().bitLength()>=2048 || publicKey instanceof java.security.interfaces.ECPublicKey ec && ec.getParams().getOrder().bitLength()>=256 || publicKey instanceof java.security.interfaces.EdECPublicKey ed && Set.of("Ed25519","Ed448").contains(ed.getParams().getName())))continue;
     // Require issuer CRL coverage for delegated responders, including no-check responders.
     boolean covered=false;
     for(var crl:crls){try{if(!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))continue;crl.verify(issuer.getPublicKey());if(crl.isRevoked(candidate))throw new CertificateRevocations.RevokedCertificateException();covered=true;}catch(CertificateRevocations.RevokedCertificateException e){throw e;}catch(Exception ignored){}}
     if(!covered)continue;
    }
    signer=candidate;break;
   }catch(CertificateRevocations.RevokedCertificateException e){throw e;}catch(Exception ignored){}
  }
  if(signer==null)throw new IllegalArgumentException("OCSP signer unauthorized");
  var answers=basic.getResponses();
  if(answers.length!=1 || !answers[0].getCertID().equals(id))throw new IllegalArgumentException("OCSP certificate mismatch");
  var answer=answers[0];
  if((answer.getCriticalExtensionOIDs()!=null && !answer.getCriticalExtensionOIDs().isEmpty()) || answer.getThisUpdate()==null || answer.getNextUpdate()==null || answer.getThisUpdate().toInstant().isAfter(now.plusSeconds(60)) || answer.getThisUpdate().toInstant().isBefore(now.minusSeconds(600)) || !answer.getNextUpdate().toInstant().isAfter(now) || !answer.getNextUpdate().after(answer.getThisUpdate()) || answer.getNextUpdate().toInstant().isAfter(answer.getThisUpdate().toInstant().plusSeconds(86400)))throw new IllegalArgumentException("OCSP answer not fresh");
  if(answer.getCertStatus() instanceof RevokedStatus)throw new CertificateRevocations.RevokedCertificateException();
  if(answer.getCertStatus()!=CertificateStatus.GOOD)throw new IllegalArgumentException("OCSP status unknown");
 }
}
