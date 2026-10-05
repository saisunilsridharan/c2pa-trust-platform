package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.Instant;
@RestController @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class SigningOptionsController {
 public record View(String id,Long revision,String label,ConfigurationController.Settings settings,Long profileRevision,String fingerprint,boolean development,boolean enabled,boolean available,Instant createdAt){}
 public record Publish(@NotBlank @Size(max=120) String label,@NotNull Long expectedProfileRevision,@NotBlank String expectedIdentityFingerprint,boolean acknowledgePublicClaims){}
 public record Enable(@NotNull Long revision,boolean enabled){}
 private final SigningOptionRepository options;private final SigningChoices choices;private final com.fasterxml.jackson.databind.ObjectMapper mapper;private final AuditService audit;private final WorkspaceRepository workspaces;
 public SigningOptionsController(SigningOptionRepository options,SigningChoices choices,com.fasterxml.jackson.databind.ObjectMapper mapper,AuditService audit,WorkspaceRepository workspaces){this.options=options;this.choices=choices;this.mapper=mapper;this.audit=audit;this.workspaces=workspaces;}
 private View view(SigningOption o)throws Exception{boolean ready;try{choices.material(o);ready=true;}catch(Exception e){ready=false;}return new View(o.id,o.revision,o.label,mapper.readValue(o.settings,ConfigurationController.Settings.class),o.profileRevision,o.fingerprint,o.development,o.enabled,ready,o.createdAt);}
 private List<View> list(boolean admin,int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var paging=org.springframework.data.domain.PageRequest.of(page,50);List<View> result=new ArrayList<>();for(var o:admin?options.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),paging):options.findByWorkspaceIdAndEnabledTrueOrderByCreatedAtDesc(WorkspaceContext.id(),paging))result.add(view(o));return result;}
 @GetMapping("/api/v1/portal/signing-options") public List<View> publicList(@RequestParam(defaultValue="0") int page)throws Exception{return list(false,page);}
 @GetMapping("/api/v1/admin/signing-options") public List<View> adminList(@RequestParam(defaultValue="0") int page)throws Exception{return list(true,page);}
 @PostMapping("/api/v1/admin/signing-options") @Transactional public View publish(@Valid @RequestBody Publish request)throws Exception{
  if(!request.acknowledgePublicClaims())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review the profile and certificate before approving");
  workspaces.lockById(WorkspaceContext.id()).orElseThrow();var selected=choices.select(null,null);
  if(!Objects.equals(selected.profileRevision(),request.expectedProfileRevision()) || !Objects.equals(selected.material().fingerprint(),request.expectedIdentityFingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Profile or certificate changed; review again");
  var o=new SigningOption();o.id=UUID.randomUUID().toString();o.workspaceId=WorkspaceContext.id();o.label=request.label().trim();o.settings=mapper.writeValueAsString(selected.settings());o.profileRevision=selected.profileRevision();o.fingerprint=selected.material().fingerprint();o.certificatePath=selected.material().certificate().toString();o.keyPath=selected.material().key().toString();o.development=selected.material().development();o.createdAt=Instant.now();options.saveAndFlush(o);audit.record("SIGNING_CHOICE_APPROVED",o.id);return view(o);
 }
 @PutMapping("/api/v1/admin/signing-options/{id}") @Transactional public View enable(@PathVariable String id,@Valid @RequestBody Enable request)throws Exception{
  workspaces.lockById(WorkspaceContext.id()).orElseThrow();var o=options.findById(id).filter(x->x.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!Objects.equals(o.revision,request.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Signing choice changed");if(request.enabled())choices.material(o);o.enabled=request.enabled();options.saveAndFlush(o);audit.record(o.enabled?"SIGNING_CHOICE_ENABLED":"SIGNING_CHOICE_WITHDRAWN",o.id);return view(o);
 }
}
