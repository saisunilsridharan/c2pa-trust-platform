package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
@Service public class SigningChoices {
 public record Selected(String optionId,Long optionRevision,ConfigurationController.Settings settings,Long profileRevision,DevelopmentIdentity.Material material){}
 private final HardwareIdentityRepository hardware;private final SigningOptionRepository options;private final ConfigurationRepository configs;private final DevelopmentIdentity identity;private final com.fasterxml.jackson.databind.ObjectMapper mapper;
 public SigningChoices(SigningOptionRepository options,ConfigurationRepository configs,DevelopmentIdentity identity,com.fasterxml.jackson.databind.ObjectMapper mapper,HardwareIdentityRepository hardware){this.hardware=hardware;this.options=options;this.configs=configs;this.identity=identity;this.mapper=mapper;}
 public Selected profile()throws Exception{
  var config=configs.findById(WorkspaceContext.id()).filter(c->c.active!=null).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Activate a profile first"));
  return new Selected(null,null,mapper.readValue(config.active,ConfigurationController.Settings.class),config.activeRevision==null?config.revision:config.activeRevision,null);
 }
 public Selected select(String id,Long revision)throws Exception {
  if(id==null || id.isBlank()){
   var profile=profile();return new Selected(null,null,profile.settings(),profile.profileRevision(),identity.material());
  }
  var option=options.findById(id).filter(o->o.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  if(!option.enabled || !Objects.equals(option.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Signing choice changed or was withdrawn; review again");
  var material=material(option);
  return new Selected(option.id,option.revision,mapper.readValue(option.settings,ConfigurationController.Settings.class),option.profileRevision,material);
 }
 public DevelopmentIdentity.Material material(SigningOption option)throws Exception {
  try{
   Path certificate=Path.of(option.certificatePath),key=Path.of(option.keyPath);
   try(var input=Files.newInputStream(certificate)){
    var leaf=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);leaf.checkValidity();
    if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded())).equals(option.fingerprint) || !(key.toString().startsWith("pkcs11:")?hardware.findById(key.toString().substring(7)).filter(i->i.workspaceId.equals(option.workspaceId) && i.fingerprint.equals(option.fingerprint) && i.testedAt!=null).isPresent():Files.isRegularFile(key)))throw new IllegalStateException();
   }
   return new DevelopmentIdentity.Material(certificate,key,option.fingerprint,option.development);
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Selected certificate is expired or unavailable; ask an administrator to replace the choice");}
 }
}
