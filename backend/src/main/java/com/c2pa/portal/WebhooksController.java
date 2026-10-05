package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/webhooks")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class WebhooksController {
 private final WebhookSettingsRepository settings;private final WebhookVersionRepository versions;private final WebhookDeliveryRepository deliveries;private final WebhookService service;private final ObjectMapper mapper;private final WorkspaceRepository workspaces;private final AuditService audit;
 public WebhooksController(WebhookSettingsRepository settings,WebhookVersionRepository versions,WebhookDeliveryRepository deliveries,WebhookService service,ObjectMapper mapper,WorkspaceRepository workspaces,AuditService audit){this.settings=settings;this.versions=versions;this.deliveries=deliveries;this.service=service;this.mapper=mapper;this.workspaces=workspaces;this.audit=audit;}
 public record Version(String id,WebhookService.Configuration configuration,Instant createdAt,Instant testedAt){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,WebhookService.Configuration configuration){}
 public record Selection(Long revision,String versionId,boolean acknowledgeActivation){}
 public record Delivery(String id,String state,int attempts,Integer statusCode,Instant createdAt,Instant nextAttemptAt){}
 private WebhookSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new WebhookSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});}
 private WebhookSettings current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Webhook settings changed");return s;}
 private WebhookVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 private Version view(WebhookVersion v)throws Exception{return new Version(v.id,service.decode(v.configuration),v.createdAt,v.testedAt);}
 private State state(WebhookSettings s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 @GetMapping @Transactional public State get()throws Exception{return state(settings());}
 @PutMapping("/draft") @Transactional public State save(@RequestBody Draft draft)throws Exception{var s=current(draft.revision());var configuration=service.validate(WorkspaceContext.id(),draft.configuration());var v=new WebhookVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.configuration=mapper.writeValueAsString(configuration);v.createdAt=Instant.now();versions.saveAndFlush(v);s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("WEBHOOK_DRAFT_SAVED",v.id);return state(s);}
 @PostMapping("/test") @Transactional public State test(@RequestBody Selection selection)throws Exception{var s=current(selection.revision());var v=version(selection.versionId());service.test(v.workspaceId,service.decode(v.configuration));v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("WEBHOOK_TESTED",v.id);return state(s);}
 @PostMapping("/activate") @Transactional public State activate(@RequestBody Selection selection)throws Exception{if(!selection.acknowledgeActivation())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge sending future job outcomes to this receiver");var s=current(selection.revision());var v=version(selection.versionId());if(v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this version first");s.activeVersion=v.id;s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("WEBHOOK_ACTIVATED",v.id);return state(s);}
 private void page(int page){if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
 @GetMapping("/history") public List<Version> history(@RequestParam(defaultValue="0") int page)throws Exception{page(page);List<Version> result=new ArrayList<>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))result.add(view(v));return result;}
 @GetMapping("/deliveries") public List<Delivery> deliveries(@RequestParam(defaultValue="0") int page){page(page);return deliveries.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)).stream().map(d->new Delivery(d.id,d.state,d.attempts,d.statusCode,d.createdAt,d.nextAttemptAt)).toList();}
 @PostMapping("/deliveries/{id}/retry") @Transactional public Map<String,String> retry(@PathVariable String id){var d=deliveries.lockById(id).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!d.state.equals("FAILED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed deliveries can be retried");d.state="PENDING";d.attempts=0;d.statusCode=null;d.nextAttemptAt=Instant.now();deliveries.saveAndFlush(d);audit.record("WEBHOOK_DELIVERY_RETRIED",id);return Map.of("state",d.state);}
}
