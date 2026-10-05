package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.cert.*;
import java.util.*;
@Service public class TrustPolicy {
 public record Configuration(String privateAnchorsPem,boolean requireTrustedSigning){}
 public record Snapshot(String versionId,Configuration configuration){}
 private final TrustSettingsRepository settings;private final TrustVersionRepository versions;private final ObjectMapper mapper;
 public TrustPolicy(TrustSettingsRepository settings,TrustVersionRepository versions,ObjectMapper mapper){this.settings=settings;this.versions=versions;this.mapper=mapper;}
 public Configuration decode(String value)throws Exception{return mapper.readValue(value,Configuration.class);}
 public Configuration validate(Configuration c)throws Exception{
  try{
   if(c==null || (c.privateAnchorsPem()!=null && c.privateAnchorsPem().length()>60000))throw new IllegalArgumentException();
   String pem=Objects.requireNonNullElse(c.privateAnchorsPem(),"").trim();
   if(pem.isEmpty()){if(c.requireTrustedSigning())throw new IllegalArgumentException();return new Configuration("",false);}
   var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
   if(certificates.isEmpty() || certificates.size()>50)throw new IllegalArgumentException();
   for(var cert:certificates){var root=(X509Certificate)cert;root.checkValidity();if(root.getBasicConstraints()<0 || (root.getKeyUsage()!=null && !root.getKeyUsage()[5]))throw new IllegalArgumentException();}
   return new Configuration(pem,c.requireTrustedSigning());
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supply valid private CA trust anchors; strict signing requires at least one anchor");}
 }
 public String snapshot(Long workspace)throws Exception{
  var id=settings.findById(workspace).map(s->s.activeVersion).orElse(null);if(id==null)return null;
  var v=versions.findById(id).filter(x->x.workspaceId.equals(workspace)).orElseThrow();return mapper.writeValueAsString(new Snapshot(v.id,decode(v.configuration)));
 }
 public String workerConfiguration(String snapshot)throws Exception{return workerConfiguration(snapshot,null);}
 public String workerConfiguration(String snapshot,String timestampSnapshot)throws Exception{
  if(snapshot==null && timestampSnapshot==null)return null;
  var s=snapshot==null?null:mapper.readValue(snapshot,Snapshot.class);var c=s==null?new Configuration("",false):s.configuration();List<Object> anchors=new ArrayList<>();
  if(c.privateAnchorsPem()!=null && !c.privateAnchorsPem().isBlank())anchors.add(Map.of("trust_anchors",c.privateAnchorsPem(),"trust_kind","manifest","trust_uri","urn:c2pa-portal:private-policy:"+s.versionId()));
  var timestamp=timestampSnapshot==null?null:mapper.readValue(timestampSnapshot,PrivateTimestamps.Snapshot.class);
  if(timestamp!=null)anchors.add(Map.of("trust_anchors",timestamp.configuration().tsaAnchorsPem(),"trust_kind","tsa","trust_uri","urn:c2pa-portal:private-tsa:"+timestamp.versionId()));
  return mapper.writeValueAsString(Map.of("versionId",s==null?"SDK_DEFAULT":s.versionId(),"source",s==null?"PRIVATE_TIMESTAMP_POLICY":"PRIVATE_WORKSPACE_POLICY","requireTrusted",c.requireTrustedSigning(),"requireTimestamp",timestamp!=null,"timestampVersionId",timestamp==null?"":timestamp.versionId(),"settings",Map.of("trust",Map.of("anchors",anchors),"verify",Map.of("verify_trust",true,"ocsp_fetch",false,"remote_manifest_fetch",false),"core",Map.of("allowed_network_hosts",List.of(),"allow_redirects",false))));
 }
}
