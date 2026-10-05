package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@RestController
@RequestMapping("/api/v1/admin/storage/providers")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class StorageProvidersController {
 private final StorageRepository storage;private final StorageVersionRepository versions;private final PrivateObjectStorage provider;private final ObjectMapper mapper;private final AuditService audit;private final WorkspaceRepository workspaces;
 public StorageProvidersController(StorageRepository storage,StorageVersionRepository versions,PrivateObjectStorage provider,ObjectMapper mapper,AuditService audit,WorkspaceRepository workspaces){this.storage=storage;this.versions=versions;this.provider=provider;this.mapper=mapper;this.audit=audit;this.workspaces=workspaces;}
 public record Version(String id,PrivateObjectStorage.Configuration configuration,Instant createdAt,Instant testedAt){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,PrivateObjectStorage.Configuration configuration){}
 public record Selection(Long revision,String versionId,boolean acknowledgeActivation){}
 private AssetStorage settings(){return storage.findById(WorkspaceContext.id()).orElseGet(()->{var s=new AssetStorage();s.id=WorkspaceContext.id();return storage.saveAndFlush(s);});}
 private StorageVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Storage version not found"));}
 private Version view(StorageVersion v)throws Exception{return new Version(v.id,provider.decode(v.configuration),v.createdAt,v.testedAt);}
 private State state(AssetStorage s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 private AssetStorage current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Storage changed; refresh before retrying");return s;}
 @GetMapping @Transactional public State get()throws Exception{return state(settings());}
 @PutMapping("/draft") @Transactional public State save(@RequestBody Draft draft)throws Exception{
  var s=current(draft.revision());var configuration=provider.validate(WorkspaceContext.id(),draft.configuration());StorageVersion v=new StorageVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.configuration=mapper.writeValueAsString(configuration);v.createdAt=Instant.now();versions.saveAndFlush(v);s.draftVersion=v.id;storage.saveAndFlush(s);audit.record("STORAGE_DRAFT_SAVED",v.id);return state(s);
 }
 @PostMapping("/test") @Transactional public State test(@RequestBody Selection selection)throws Exception{
  var s=current(selection.revision());var v=version(selection.versionId());provider.test(v.workspaceId,provider.decode(v.configuration));v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("STORAGE_CONNECTION_TESTED",v.id);return state(s);
 }
 @PostMapping("/activate") @Transactional public State activate(@RequestBody Selection selection)throws Exception{
  if(!selection.acknowledgeActivation())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge changing storage for future jobs");var s=current(selection.revision());var v=version(selection.versionId());if(v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this version before activation");s.activeVersion=v.id;s.draftVersion=v.id;storage.saveAndFlush(s);audit.record("STORAGE_PROVIDER_ACTIVATED",v.id);return state(s);
 }
 @GetMapping("/history") public List<Version> history(@RequestParam(defaultValue="0") int page)throws Exception{
  if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);List<Version> result=new ArrayList<>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))result.add(view(v));return result;
 }
}
