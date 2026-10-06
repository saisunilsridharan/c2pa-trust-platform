package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.cert.*;
import java.time.Instant;
import java.util.*;
@Service public class CertificateRevocations {
 public static class RevokedCertificateException extends CertificateException { public RevokedCertificateException(){super("Certificate is revoked");} }
 public record Configuration(boolean enabled,boolean requireCoveredSigning,String issuerCertificatesPem,String crlsPem,OnlineOcsp.Configuration onlineOcsp){
  public Configuration(boolean enabled,boolean requireCoveredSigning,String issuerCertificatesPem,String crlsPem){this(enabled,requireCoveredSigning,issuerCertificatesPem,crlsPem,null);}
 }
 public record Summary(int issuers,int crls,int revokedEntries,Instant nextUpdate){}
 private record Validated(List<X509Certificate> issuers,List<X509CRL> crls,Summary summary){}
 private final RevocationSettingsRepository settings;private final RevocationVersionRepository versions;private final ObjectMapper mapper;
 public CertificateRevocations(RevocationSettingsRepository settings,RevocationVersionRepository versions,ObjectMapper mapper){this.settings=settings;this.versions=versions;this.mapper=mapper;}
 public record Status(boolean configured,boolean enabled,boolean current,boolean requireCoveredSigning,String versionId,Instant nextUpdate){}
 public Status status(Long workspace)throws Exception{String id=settings.findById(workspace).map(v->v.activeVersion).orElse(null);if(id==null)return new Status(false,false,true,false,null,null);var version=versions.findById(id).filter(v->v.workspaceId.equals(workspace)).orElseThrow();var c=decode(version.configuration);try{return new Status(true,c.enabled(),true,c.requireCoveredSigning(),id,summary(c).nextUpdate());}catch(Exception e){return new Status(true,c.enabled(),false,c.requireCoveredSigning(),id,null);}}
 public Configuration decode(String json)throws Exception{return mapper.readValue(json,Configuration.class);}
 private boolean signs(X509CRL crl,X509Certificate issuer){try{if(!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))return false;crl.verify(issuer.getPublicKey());return true;}catch(Exception e){return false;}}
 private Validated validated(Configuration c)throws Exception{
  if(c==null)throw new IllegalArgumentException();if(!c.enabled())return new Validated(List.of(),List.of(),new Summary(0,0,0,null));
  OnlineOcsp.validate(c.onlineOcsp());
  if(c.issuerCertificatesPem()==null || c.crlsPem()==null || c.issuerCertificatesPem().length()>60000 || c.crlsPem().length()>400000)throw new IllegalArgumentException();
  if(!c.issuerCertificatesPem().matches("(?s)(?:\\s*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----\\s*)+") || !c.crlsPem().matches("(?s)(?:\\s*-----BEGIN X509 CRL-----[A-Za-z0-9+/=\\s]+-----END X509 CRL-----\\s*)+"))throw new IllegalArgumentException();
  var factory=CertificateFactory.getInstance("X.509");var issuers=factory.generateCertificates(new ByteArrayInputStream(c.issuerCertificatesPem().getBytes(java.nio.charset.StandardCharsets.US_ASCII))).stream().map(v->(X509Certificate)v).toList();
  var crls=factory.generateCRLs(new ByteArrayInputStream(c.crlsPem().getBytes(java.nio.charset.StandardCharsets.US_ASCII))).stream().map(v->(X509CRL)v).toList();
  if(issuers.isEmpty() || issuers.size()>50 || crls.isEmpty() || crls.size()>50)throw new IllegalArgumentException();
  for(var issuer:issuers){issuer.checkValidity();var key=issuer.getPublicKey();if(!(key instanceof java.security.interfaces.RSAPublicKey rsa && rsa.getModulus().bitLength()>=2048 || key instanceof java.security.interfaces.ECPublicKey ec && ec.getParams().getOrder().bitLength()>=256 || key instanceof java.security.interfaces.EdECPublicKey ed && Set.of("Ed25519","Ed448").contains(ed.getParams().getName())))throw new IllegalArgumentException();if(issuer.getBasicConstraints()<0 || issuer.getKeyUsage()!=null && (issuer.getKeyUsage().length<7 || !issuer.getKeyUsage()[6]))throw new IllegalArgumentException();}
  Instant now=Instant.now(),next=null;int entries=0;var seen=new HashSet<String>();
  for(var crl:crls){
   if(crl.getThisUpdate()==null || crl.getNextUpdate()==null || crl.getThisUpdate().toInstant().isAfter(now.plusSeconds(60)) || !crl.getNextUpdate().toInstant().isAfter(now) || !crl.getNextUpdate().after(crl.getThisUpdate()) || crl.getExtensionValue("2.5.29.27")!=null || crl.getExtensionValue("2.5.29.28")!=null || crl.getCriticalExtensionOIDs()!=null && !crl.getCriticalExtensionOIDs().isEmpty())throw new IllegalArgumentException();
   if(!Set.of("1.2.840.113549.1.1.11","1.2.840.113549.1.1.12","1.2.840.113549.1.1.13","1.2.840.10045.4.3.2","1.2.840.10045.4.3.3","1.2.840.10045.4.3.4","1.3.101.112","1.3.101.113").contains(crl.getSigAlgOID()))throw new IllegalArgumentException();
   var issuer=issuers.stream().filter(v->signs(crl,v)).findFirst().orElseThrow();String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(issuer.getPublicKey().getEncoded()));if(!seen.add(key))throw new IllegalArgumentException();
   var revoked=crl.getRevokedCertificates();if(revoked!=null)for(var entry:revoked){if(entry.getCertificateIssuer()!=null || entry.getCriticalExtensionOIDs()!=null && !entry.getCriticalExtensionOIDs().isEmpty() || entry.getRevocationReason()==CRLReason.REMOVE_FROM_CRL)throw new IllegalArgumentException();entries++;}
   var expiry=crl.getNextUpdate().toInstant();if(next==null || expiry.isBefore(next))next=expiry;
  }
  if(issuers.stream().anyMatch(issuer->crls.stream().noneMatch(crl->signs(crl,issuer))))throw new IllegalArgumentException();
  return new Validated(issuers,crls,new Summary(issuers.size(),crls.size(),entries,next));
 }
 public Configuration validate(Configuration c)throws Exception{try{validated(c);return c.enabled()?c:new Configuration(false,false,"","");}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supply current complete CRLs signed by pinned CA issuers with CRL-signing usage. Delta, scoped/indirect, duplicate, stale and unsupported CRLs are rejected.");}}
 public Summary summary(Configuration c)throws Exception{return validated(c).summary();}
 public void check(Configuration c,Collection<X509Certificate> chain)throws Exception{check(c,chain,null);}
 public void check(Configuration c,Collection<X509Certificate> chain,String proxyEndpoint)throws Exception{
  var policy=validated(c);if(!c.enabled())return;if(chain.isEmpty() || chain.size()>10)throw new IllegalArgumentException();
  var certificates=new ArrayList<>(chain);
  for(int i=0;i<certificates.size();i++){
   var certificate=certificates.get(i);boolean selfSigned=false;try{certificate.verify(certificate.getPublicKey());selfSigned=certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal());}catch(Exception ignored){}
   if(i>0 && i==certificates.size()-1 && selfSigned)continue;
   var issuer=policy.issuers().stream().filter(v->{try{if(!certificate.getIssuerX500Principal().equals(v.getSubjectX500Principal()))return false;certificate.verify(v.getPublicKey());return true;}catch(Exception e){return false;}}).findFirst().orElse(null);
   if(issuer==null){if(c.requireCoveredSigning())throw new IllegalStateException("Certificate issuer is not covered by the active CRL policy");continue;}
   var crl=policy.crls().stream().filter(v->signs(v,issuer)).findFirst().orElseThrow();if(crl.isRevoked(certificate))throw new RevokedCertificateException();
   if(c.onlineOcsp()!=null)OnlineOcsp.check(c.onlineOcsp(),certificate,issuer,policy.crls(),proxyEndpoint);
  }
 }
 public void enforce(Long workspace,Path certificate)throws Exception{
  try{String id=settings.findById(workspace).map(s->s.activeVersion).orElse(null);if(id==null)return;var version=versions.findById(id).filter(v->v.workspaceId.equals(workspace)).orElseThrow();var configuration=decode(version.configuration);if(!configuration.enabled())return;
   try(var input=Files.newInputStream(certificate)){check(configuration,CertificateFactory.getInstance("X.509").generateCertificates(input).stream().map(v->(X509Certificate)v).toList());}
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Active revocation policy rejected signing. Review revoked certificates, coverage and CRL freshness.");}
 }
}
