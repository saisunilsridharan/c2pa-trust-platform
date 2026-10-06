package com.c2pa.portal;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.security.*;
import java.security.cert.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
@Service public class OfficialPublicTrust {
 public static final String SIGNER_SOURCE="https://raw.githubusercontent.com/c2pa-org/conformance-public/main/trust-list/C2PA-TRUST-LIST.json";
 public static final String TSA_SOURCE="https://raw.githubusercontent.com/c2pa-org/conformance-public/main/trust-list/C2PA-TSA-TRUST-LIST.json";
 public record Configuration(boolean enabled,int maxAgeHours,String proxyEndpoint,String tlsCaPem,boolean acknowledgeTrustedProxy){}
 public record Anchor(X509Certificate certificate,Instant trustedFrom){}
 public record ListInfo(long sequence,Instant issuedAt,Instant nextUpdate,List<Anchor> anchors,String sha256){}
 public record Summary(boolean enabled,boolean current,int signerAnchors,int tsaAnchors,Instant expiresAt,String signerSha256,String tsaSha256){}
 private final ObjectMapper mapper;private final PublicTrustSettingsRepository settings;private final PublicTrustVersionRepository versions;private final WorkerExecution execution;
 public OfficialPublicTrust(ObjectMapper mapper,PublicTrustSettingsRepository settings,PublicTrustVersionRepository versions,WorkerExecution execution){this.mapper=mapper;this.settings=settings;this.versions=versions;this.execution=execution;}
 public Configuration decode(String value)throws Exception{return mapper.readValue(value,Configuration.class);}
 public Configuration validate(Configuration c)throws Exception{
  if(c==null)throw new IllegalArgumentException();if(!c.enabled())return new Configuration(false,24,"","",false);
  if(c.maxAgeHours()<1 || c.maxAgeHours()>168 || c.tlsCaPem()!=null && c.tlsCaPem().length()>12000)throw new IllegalArgumentException();PrivateObjectStorage.trust(c.tlsCaPem());
  if(c.proxyEndpoint()!=null && !c.proxyEndpoint().isBlank()){
   if(!c.acknowledgeTrustedProxy() || c.proxyEndpoint().length()>2000)throw new IllegalArgumentException();var uri=URI.create(c.proxyEndpoint());
   if(!"http".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath()) || uri.getPort()<1 || uri.getPort()>65535)throw new IllegalArgumentException();
   for(var address:InetAddress.getAllByName(uri.getHost()))if(address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress())throw new IllegalArgumentException();
  }return c;
 }
 private String fetch(String source,Configuration c)throws Exception{
  var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
  if(c.proxyEndpoint()!=null && !c.proxyEndpoint().isBlank()){var proxy=URI.create(c.proxyEndpoint());builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(),proxy.getPort())));}
  var managers=PrivateObjectStorage.trust(c.tlsCaPem());if(managers!=null){var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(null,managers,null);builder.sslContext(ssl);}
  try(var client=builder.build()){var response=client.send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(20)).header("Accept","application/json").GET().build(),info->new BoundedHttpBody(1000000));if(response.statusCode()!=200 || response.body().length==0)throw new IOException("Official source unavailable");return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(response.body())).toString();}
 }
 public void download(PublicTrustVersion v)throws Exception{var c=validate(decode(v.configuration));if(!c.enabled())return;v.signerList=fetch(SIGNER_SOURCE,c);v.tsaList=fetch(TSA_SOURCE,c);v.fetchedAt=Instant.now();if(!summary(v).current())throw new IllegalArgumentException("Official lists expired");}
 public ListInfo parse(String json,boolean tsa)throws Exception{
  if(json==null || json.length()>1000000)throw new IllegalArgumentException();var list=mapper.readTree(json).path("LoTE");var info=list.path("ListAndSchemeInformation");
  if(info.path("LoTEVersionIdentifier").asInt()!=1 || !info.path("LoTESequenceNumber").canConvertToLong() || info.path("LoTESequenceNumber").asLong()<1)throw new IllegalArgumentException();
  boolean name=false;for(var item:info.path("SchemeName"))if(item.path("value").asText().equals(tsa?"C2PA TSA Trust List":"C2PA Trust List"))name=true;if(!name)throw new IllegalArgumentException();
  Instant issued=Instant.parse(info.path("ListIssueDateTime").asText()),next=Instant.parse(info.path("NextUpdate").asText());if(issued.isAfter(Instant.now().plusSeconds(60)) || !next.isAfter(issued))throw new IllegalArgumentException();
  var anchors=new ArrayList<Anchor>();var seen=new HashSet<String>();var entities=list.path("TrustedEntitiesList");if(!entities.isArray() || entities.size()>100)throw new IllegalArgumentException();
  for(var entity:entities)for(var service:entity.path("TrustedEntityServices")){
   if(service.has("ServiceHistory"))throw new IllegalArgumentException("Unsupported historical service status");var entry=service.path("ServiceInformation");if(!entry.path("ServiceStatus").asText().equals("http://c2pa.org/conformance/trust-list/trusted"))continue;
   Instant start=Instant.parse(entry.path("StatusStartingTime").asText());for(var identity:entry.path("ServiceDigitalIdentity").path("X509Certificates")){
    String encoded=identity.path("val").asText();if(encoded.length()>16000)throw new IllegalArgumentException();byte[] der=Base64.getDecoder().decode(encoded);
    var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));if(!Arrays.equals(der,cert.getEncoded()))throw new IllegalArgumentException("Noncanonical certificate DER");var key=cert.getPublicKey();
    if(cert.getBasicConstraints()<0 || cert.getKeyUsage()!=null && (cert.getKeyUsage().length<6 || !cert.getKeyUsage()[5]) || !(key instanceof java.security.interfaces.RSAPublicKey rsa && rsa.getModulus().bitLength()>=2048 || key instanceof java.security.interfaces.ECPublicKey ec && ec.getParams().getOrder().bitLength()>=256 || key instanceof java.security.interfaces.EdECPublicKey ed && Set.of("Ed25519","Ed448").contains(ed.getParams().getName())))throw new IllegalArgumentException();
    String fp=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));if(!seen.add(fp))throw new IllegalArgumentException("Duplicate anchor");anchors.add(new Anchor(cert,start));if(anchors.size()>100)throw new IllegalArgumentException();
   }
  }
  if(anchors.isEmpty())throw new IllegalArgumentException();return new ListInfo(info.path("LoTESequenceNumber").asLong(),issued,next,List.copyOf(anchors),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
 }
 public Summary summary(PublicTrustVersion v)throws Exception{
  var c=validate(decode(v.configuration));if(!c.enabled())return new Summary(false,true,0,0,null,null,null);var signer=parse(v.signerList,false);var tsa=parse(v.tsaList,true);if(v.fetchedAt==null || v.fetchedAt.isAfter(Instant.now().plusSeconds(60)))throw new IllegalArgumentException();Instant expires=v.fetchedAt.plusSeconds(c.maxAgeHours()*3600L);if(signer.nextUpdate().isBefore(expires))expires=signer.nextUpdate();if(tsa.nextUpdate().isBefore(expires))expires=tsa.nextUpdate();return new Summary(true,expires.isAfter(Instant.now()),signer.anchors().size(),tsa.anchors().size(),expires,signer.sha256(),tsa.sha256());
 }
 public void preventRollback(PublicTrustVersion old,PublicTrustVersion next)throws Exception{if(old==null || !decode(old.configuration).enabled() || !decode(next.configuration).enabled())return;var a=parse(old.signerList,false);var b=parse(next.signerList,false);var x=parse(old.tsaList,true);var y=parse(next.tsaList,true);if(b.sequence()<a.sequence() || y.sequence()<x.sequence() || b.issuedAt().isBefore(a.issuedAt()) || y.issuedAt().isBefore(x.issuedAt()) || b.sequence()==a.sequence() && !b.sha256().equals(a.sha256()) || y.sequence()==x.sequence() && !y.sha256().equals(x.sha256()))throw new IllegalArgumentException("Official list rollback");}
 private String pem(ListInfo list,Instant reference)throws Exception{var out=new StringBuilder();for(var anchor:list.anchors()){if(reference!=null){if(anchor.trustedFrom().isAfter(reference))continue;try{anchor.certificate().checkValidity(Date.from(reference));}catch(CertificateException e){continue;}}out.append("-----BEGIN CERTIFICATE-----\n").append(Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(anchor.certificate().getEncoded())).append("\n-----END CERTIFICATE-----\n");}return out.toString();}
 // The SDK checks timestamp trust against all loaded anchors. Load only TSA roots here;
 // independently validate the content signer against signer roots with bounded offline PKIX.
 private String policy(PublicTrustVersion v,Instant reference)throws Exception{return mapper.writeValueAsString(Map.of("versionId",v.id,"source","OFFICIAL_C2PA_TRUST_LIST","requireTrusted",false,"requireTimestamp",false,"timestampVersionId",v.id,"settings",Map.of("trust",Map.of("trust_config","1.3.6.1.4.1.62558.2.1","anchors",List.of(Map.of("trust_anchors",pem(parse(v.tsaList,true),reference),"trust_kind","tsa","trust_uri",TSA_SOURCE))),"verify",Map.of("verify_trust",true,"ocsp_fetch",false,"remote_manifest_fetch",false),"core",Map.of("allowed_network_hosts",List.of(),"allow_redirects",false))));}
 static boolean timestampTrusted(JsonNode report){for(var item:report.path("validation_results").path("activeManifest").path("success"))if(item.path("code").asText().equals("timeStamp.trusted"))return true;return false;}
 private JsonNode inspect(PublicTrustVersion v,Path input,Path folder,Instant reference)throws Exception{
  Path report=folder.resolve("official-report.json");int exit=execution.inspect(v.workspaceId,Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize(),input,policy(v,reference),report,folder.resolve("official-error.log"),30);if(exit!=0 || Files.size(report)>8*1024*1024L)throw new IOException("Official SDK inspection failed");return mapper.readTree(report.toFile());
 }
 boolean signingChainTrusted(PublicTrustVersion v,String pem,Instant reference)throws Exception{
  if(pem.length()>60000)throw new IllegalArgumentException();var factory=CertificateFactory.getInstance("X.509");var chain=new ArrayList<X509Certificate>();
  for(var cert:factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII))))chain.add((X509Certificate)cert);
  if(chain.isEmpty() || chain.size()>16 || new HashSet<>(chain).size()!=chain.size())return false;
  for(var anchor:parse(v.signerList,false).anchors()){
   if(anchor.trustedFrom().isAfter(reference))continue;try{anchor.certificate().checkValidity(Date.from(reference));}catch(CertificateException e){continue;}
   int stop=chain.indexOf(anchor.certificate());var path=stop<0?chain:chain.subList(0,stop);if(path.isEmpty())continue;
   var parameters=new PKIXParameters(Set.of(new TrustAnchor(anchor.certificate(),null)));parameters.setDate(Date.from(reference));parameters.setRevocationEnabled(false);
   try{CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(path),parameters);return true;}catch(CertPathValidatorException ignored){}
  }return false;
 }
 public JsonNode evaluate(PublicTrustVersion v,Path input,Path folder)throws Exception{
  var s=summary(v);if(!s.enabled() || !s.current())throw new IllegalStateException("Official trust lists are disabled or stale");var first=inspect(v,input,folder,null);Instant reference=Instant.now();
  if(timestampTrusted(first)){String time=first.path("manifests").path(first.path("active_manifest").asText()).path("signature_info").path("time").asText();if(time.isBlank())throw new IllegalStateException("Trusted timestamp time unavailable");reference=Instant.parse(time);if(reference.isAfter(Instant.now().plusSeconds(60)))throw new IllegalStateException("Timestamp in future");}
  var report=inspect(v,input,folder,reference);
  if(timestampTrusted(first) && !timestampTrusted(report)){reference=Instant.now();report=inspect(v,input,folder,reference);}
  else if(timestampTrusted(report)){String time=report.path("manifests").path(report.path("active_manifest").asText()).path("signature_info").path("time").asText();if(!Instant.parse(time).equals(reference))throw new IllegalStateException("Timestamp reference changed during validation");}
  boolean trusted=Set.of("Valid","Trusted").contains(report.path("validation_state").asText()) && signingChainTrusted(v,report.path("portal_signing_certificate_chain").asText(),reference);
  var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(report.path("portal_signing_certificate_chain").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  trusted=trusted && cert.getExtendedKeyUsage()!=null && cert.getExtendedKeyUsage().contains("1.3.6.1.4.1.62558.2.1");
  return mapper.createObjectNode().put("configured",true).put("current",true).put("publicTrustVerified",trusted).put("publicTimestampTrusted",timestampTrusted(report)).put("onlineRevocationChecked",false).put("versionId",v.id).put("signerSource",SIGNER_SOURCE).put("tsaSource",TSA_SOURCE).put("signerSha256",s.signerSha256()).put("tsaSha256",s.tsaSha256()).put("referenceTime",reference.toString());
 }
 public JsonNode evaluateActive(Long workspace,Path input,Path folder)throws Exception{
  String id=settings.findById(workspace).map(s->s.activeVersion).orElse(null);if(id==null)return mapper.valueToTree(Map.of("configured",false,"publicTrustVerified",false));
  var v=versions.findById(id).filter(row->row.workspaceId.equals(workspace)).orElseThrow();try{var s=summary(v);if(!s.enabled())return mapper.valueToTree(Map.of("configured",true,"enabled",false,"publicTrustVerified",false));if(!s.current())return mapper.valueToTree(Map.of("configured",true,"current",false,"publicTrustVerified",false,"status","STALE"));return evaluate(v,input,folder);}catch(Exception e){return mapper.valueToTree(Map.of("configured",true,"publicTrustVerified",false,"status","UNAVAILABLE_OR_INVALID"));}
 }
}
