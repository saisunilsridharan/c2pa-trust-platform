package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/private-ca")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class PrivateCaController {
 public record Version(String id,PrivateCa.Configuration configuration,Instant createdAt,Instant testedAt){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,PrivateCa.Configuration configuration){}
 public record Selection(Long revision,String versionId,boolean acknowledgePrivateIssuance){}
 public record Issue(Long revision,String versionId,String identityId,String expectedIdentityFingerprint,HardwareCertificateRequests.Subject subject,boolean acknowledgePrivateIssuance){}
 public record Result(String providerVersion,HardwareIdentitiesController.View identity,State state){}
 private final PrivateCaSettingsRepository settings;private final PrivateCaVersionRepository versions;private final PrivateCa ca;private final ObjectMapper mapper;private final WorkspaceRepository workspaces;private final AuditService audit;private final TransactionTemplate transactions;
 public PrivateCaController(PrivateCaSettingsRepository settings,PrivateCaVersionRepository versions,PrivateCa ca,ObjectMapper mapper,WorkspaceRepository workspaces,AuditService audit,PlatformTransactionManager manager){this.settings=settings;this.versions=versions;this.ca=ca;this.mapper=mapper;this.workspaces=workspaces;this.audit=audit;this.transactions=new TransactionTemplate(manager);}
 private PrivateCaSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new PrivateCaSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});}
 private PrivateCaVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 private Version view(PrivateCaVersion v)throws Exception{return new Version(v.id,ca.decode(v.configuration),v.createdAt,v.testedAt);}
 private State state(PrivateCaSettings s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 private PrivateCaSettings current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Private CA configuration changed");return s;}
 private void acknowledge(boolean accepted){if(!accepted)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review and authorize private CA certificate issuance; test requests also issue real certificate drafts");}
 @GetMapping @Transactional public State get()throws Exception{return state(settings());}
 @PutMapping("/draft") @Transactional public State save(@RequestBody Draft request)throws Exception{var s=current(request.revision());var v=new PrivateCaVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.configuration=mapper.writeValueAsString(ca.validate(v.workspaceId,request.configuration()));v.createdAt=Instant.now();versions.saveAndFlush(v);s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("PRIVATE_CA_DRAFT_SAVED",v.id);return state(s);}
 @GetMapping("/history") public List<Version> history(@RequestParam(defaultValue="0") int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var result=new ArrayList<Version>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),PageRequest.of(page,50)))result.add(view(v));return result;}
 private record Captured(String id,PrivateCa.Configuration configuration){}
 private Captured capture(Issue request,boolean active){acknowledge(request.acknowledgePrivateIssuance());return transactions.execute(status->{var v=version(request.versionId());var s=current(request.revision());if(active && !Objects.equals(s.activeVersion,v.id))throw new ResponseStatusException(HttpStatus.CONFLICT,"Select the active CA version");try{return new Captured(v.id,ca.decode(v.configuration));}catch(Exception e){throw new IllegalStateException(e);}});}
 @PostMapping("/test") public Result test(@RequestBody Issue request)throws Exception{
  var selected=capture(request,false);var identity=selected.configuration().enabled()?ca.issue(WorkspaceContext.id(),selected.id(),selected.configuration(),request.identityId(),request.expectedIdentityFingerprint(),request.subject()):null;
  var state=transactions.execute(status->{workspaces.lockById(WorkspaceContext.id()).orElseThrow();var v=version(selected.id());v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("PRIVATE_CA_ISSUANCE_TESTED",v.id);try{return state(settings());}catch(Exception e){throw new IllegalStateException(e);}});return new Result(selected.id(),identity,state);
 }
 @PostMapping("/activate") @Transactional public State activate(@RequestBody Selection request)throws Exception{acknowledge(request.acknowledgePrivateIssuance());var v=version(request.versionId());var s=current(request.revision());if(v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this issuer version first");s.activeVersion=v.id;s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("PRIVATE_CA_PROVIDER_ACTIVATED",v.id);return state(s);}
 @PostMapping("/issue") public Result issue(@RequestBody Issue request)throws Exception{var selected=capture(request,true);var identity=ca.issue(WorkspaceContext.id(),selected.id(),selected.configuration(),request.identityId(),request.expectedIdentityFingerprint(),request.subject());return new Result(selected.id(),identity,get());}
}
