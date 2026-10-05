package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
@RestController @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class TimestampController {
 public record Version(String id,PrivateTimestamps.Configuration configuration,Instant createdAt,Instant testedAt){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,PrivateTimestamps.Configuration configuration){}
 public record Selection(Long revision,String versionId,boolean acknowledgePrivateTimestamp){}
 public record Test(Long revision,String versionId,String signingOptionId,Long signingOptionRevision){}
 private final TimestampSettingsRepository settings;private final TimestampVersionRepository versions;private final PrivateTimestamps timestamps;private final SigningChoices choices;private final WorkerExecution execution;private final TrustPolicy trust;private final com.fasterxml.jackson.databind.ObjectMapper mapper;private final WorkspaceRepository workspaces;private final AuditService audit;
 public TimestampController(TimestampSettingsRepository settings,TimestampVersionRepository versions,PrivateTimestamps timestamps,SigningChoices choices,WorkerExecution execution,TrustPolicy trust,com.fasterxml.jackson.databind.ObjectMapper mapper,WorkspaceRepository workspaces,AuditService audit){this.settings=settings;this.versions=versions;this.timestamps=timestamps;this.choices=choices;this.execution=execution;this.trust=trust;this.mapper=mapper;this.workspaces=workspaces;this.audit=audit;}
 private TimestampSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new TimestampSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});}
 private TimestampVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 private Version view(TimestampVersion v)throws Exception{return new Version(v.id,timestamps.decode(v.configuration),v.createdAt,v.testedAt);}
 private State state(TimestampSettings s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 private TimestampSettings current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Timestamp configuration changed");return s;}
 @GetMapping("/api/v1/admin/timestamps") @Transactional public State get()throws Exception{return state(settings());}
 @GetMapping("/api/v1/portal/timestamps") public Map<String,Object> status()throws Exception{String snapshot=timestamps.snapshot(WorkspaceContext.id());if(snapshot==null)return Map.of("enabled",false);return Map.of("enabled",true,"versionId",mapper.readTree(snapshot).path("versionId").asText(),"source","PRIVATE_WORKSPACE_TSA");}
 @PutMapping("/api/v1/admin/timestamps/draft") @Transactional public State save(@RequestBody Draft request)throws Exception{var s=current(request.revision());var v=new TimestampVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.configuration=mapper.writeValueAsString(timestamps.validate(v.workspaceId,request.configuration()));v.createdAt=Instant.now();versions.saveAndFlush(v);s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("PRIVATE_TSA_DRAFT_SAVED",v.id);return state(s);}
 @GetMapping("/api/v1/admin/timestamps/history") public List<Version> history(@RequestParam(defaultValue="0") int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);List<Version> result=new ArrayList<>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))result.add(view(v));return result;}
 @PostMapping("/api/v1/admin/timestamps/test") @Transactional public State test(@RequestBody Test request)throws Exception{
  var s=current(request.revision());var v=version(request.versionId());var c=timestamps.decode(v.configuration);
  if(c.enabled()){
   var selected=choices.select(request.signingOptionId(),request.signingOptionRevision());var material=selected.material();Path directory=Files.createTempDirectory("c2pa-timestamp-probe-");
   try{
    Files.write(directory.resolve("probe.png"),Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));Files.writeString(directory.resolve("manifest.json"),"{\"title\":\"Private timestamp readiness probe\",\"format\":\"image/png\",\"claim_generator_info\":[{\"name\":\"C2PA Trust Portal\"}]}");
    int result=execution.sign(v.workspaceId,Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize(),directory.resolve("probe.png"),directory.resolve("signed.png"),directory.resolve("manifest.json"),material.certificate(),material.key().toString(),directory.resolve("report.json"),directory.resolve("error.log"),45,trust.snapshot(v.workspaceId),mapper.writeValueAsString(new PrivateTimestamps.Snapshot(v.id,c)));
    if(result!=0 || !Files.isRegularFile(directory.resolve("signed.png")))throw new IllegalStateException();
   }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Timestamped signing probe failed; check endpoint, TLS, authentication, TSA anchors and signing identity");}
   finally{try(var files=Files.list(directory)){for(var file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(directory);}
  }
  v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("PRIVATE_TSA_SIGNING_TESTED",v.id);return state(s);
 }
 @PostMapping("/api/v1/admin/timestamps/activate") @Transactional public State activate(@RequestBody Selection selection)throws Exception{if(!selection.acknowledgePrivateTimestamp())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge private timestamp trust");var s=current(selection.revision());var v=version(selection.versionId());if(v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this timestamp version first");s.activeVersion=v.id;s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("PRIVATE_TSA_PROVIDER_ACTIVATED",v.id);return state(s);}
}
