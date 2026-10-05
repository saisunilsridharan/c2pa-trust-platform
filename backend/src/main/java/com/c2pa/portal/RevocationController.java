package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.cert.*;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/revocation")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class RevocationController {
 public record Version(String id,CertificateRevocations.Configuration configuration,Instant createdAt,Instant testedAt,boolean current,CertificateRevocations.Summary summary){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,CertificateRevocations.Configuration configuration){}
 public record Test(Long revision,String versionId,String signingChoiceId,boolean expectRevoked){}
 public record Selection(Long revision,String versionId,boolean acknowledgeSigningEnforcement){}
 private final RevocationSettingsRepository settings;private final RevocationVersionRepository versions;private final SigningOptionRepository choices;private final CertificateRevocations revocations;private final WorkspaceRepository workspaces;private final ObjectMapper mapper;private final AuditService audit;
 public RevocationController(RevocationSettingsRepository settings,RevocationVersionRepository versions,SigningOptionRepository choices,CertificateRevocations revocations,WorkspaceRepository workspaces,ObjectMapper mapper,AuditService audit){this.settings=settings;this.versions=versions;this.choices=choices;this.revocations=revocations;this.workspaces=workspaces;this.mapper=mapper;this.audit=audit;}
 private RevocationSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{workspaces.lockById(WorkspaceContext.id()).orElseThrow();return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new RevocationSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});});}
 private RevocationVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 private Version view(RevocationVersion v)throws Exception{var c=revocations.decode(v.configuration);try{return new Version(v.id,c,v.createdAt,v.testedAt,true,revocations.summary(c));}catch(Exception e){return new Version(v.id,c,v.createdAt,v.testedAt,false,null);}}
 private State state(RevocationSettings s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 private RevocationSettings current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Revocation policy changed");return s;}
 @GetMapping @Transactional public State get()throws Exception{return state(settings());}
 @GetMapping("/history") public List<Version> history(@RequestParam(defaultValue="0")int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var rows=new ArrayList<Version>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))rows.add(view(v));return rows;}
 @PutMapping("/draft") @Transactional public State save(@RequestBody Draft request)throws Exception{var s=current(request.revision());var v=new RevocationVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.configuration=mapper.writeValueAsString(revocations.validate(request.configuration()));v.createdAt=Instant.now();versions.saveAndFlush(v);s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("CERTIFICATE_REVOCATION_DRAFT_SAVED",v.id);return state(s);}
 @PostMapping("/test") @Transactional public State test(@RequestBody Test request)throws Exception{var v=version(request.versionId());var s=current(request.revision());var c=revocations.validate(revocations.decode(v.configuration));if(c.enabled()){
  var choice=choices.findById(Objects.requireNonNullElse(request.signingChoiceId(),"")).filter(o->o.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));boolean revoked=false;
  try(var input=Files.newInputStream(Path.of(choice.certificatePath))){try{revocations.check(c,CertificateFactory.getInstance("X.509").generateCertificates(input).stream().map(cert->(X509Certificate)cert).toList());}catch(CertificateRevocations.RevokedCertificateException e){revoked=true;}}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choice did not satisfy issuer coverage, signature or freshness requirements");}
  if(revoked!=request.expectRevoked())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Revocation result differs from the expected test outcome");
 }else if(request.expectRevoked())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Disabled enforcement cannot revoke a choice");
 v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("CERTIFICATE_REVOCATION_TESTED",v.id+":"+(request.expectRevoked()?"REVOKED":"ALLOWED"));return state(s);}
 @PostMapping("/activate") @Transactional public State activate(@RequestBody Selection request)throws Exception{if(!request.acknowledgeSigningEnforcement())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge current revocation enforcement applies to new and queued signing attempts");var v=version(request.versionId());var s=current(request.revision());if(v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this version first");revocations.validate(revocations.decode(v.configuration));s.activeVersion=v.id;s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("CERTIFICATE_REVOCATION_ACTIVATED",v.id);return state(s);}
}
