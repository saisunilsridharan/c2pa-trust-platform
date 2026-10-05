package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
@RestController @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class TrustPolicyController {
 public record Version(String id,TrustPolicy.Configuration configuration,Instant createdAt,Instant testedAt){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,TrustPolicy.Configuration configuration){}
 public record Selection(Long revision,String versionId,boolean acknowledgePrivatePolicy){}
 private final TrustSettingsRepository settings;private final TrustVersionRepository versions;private final TrustPolicy policy;private final com.fasterxml.jackson.databind.ObjectMapper mapper;private final WorkspaceRepository workspaces;private final AuditService audit;
 public TrustPolicyController(TrustSettingsRepository settings,TrustVersionRepository versions,TrustPolicy policy,com.fasterxml.jackson.databind.ObjectMapper mapper,WorkspaceRepository workspaces,AuditService audit){this.settings=settings;this.versions=versions;this.policy=policy;this.mapper=mapper;this.workspaces=workspaces;this.audit=audit;}
 private TrustSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new TrustSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});}
 private TrustVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 private Version view(TrustVersion v)throws Exception{return new Version(v.id,policy.decode(v.configuration),v.createdAt,v.testedAt);}
 private State state(TrustSettings s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 private TrustSettings current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Trust policy changed");return s;}
 @GetMapping("/api/v1/admin/trust-policy") @Transactional public State get()throws Exception{return state(settings());}
 @GetMapping("/api/v1/portal/trust-policy") public Map<String,Object> publicPolicy()throws Exception{String snapshot=policy.snapshot(WorkspaceContext.id());if(snapshot==null)return Map.of("configured",false,"requireTrustedSigning",false,"source","SDK_DEFAULT");var s=mapper.readValue(snapshot,TrustPolicy.Snapshot.class);return Map.of("configured",true,"versionId",s.versionId(),"requireTrustedSigning",s.configuration().requireTrustedSigning(),"source","PRIVATE_WORKSPACE_POLICY");}
 @PutMapping("/api/v1/admin/trust-policy/draft") @Transactional public State save(@RequestBody Draft request)throws Exception{var s=current(request.revision());var v=new TrustVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.configuration=mapper.writeValueAsString(policy.validate(request.configuration()));v.createdAt=Instant.now();versions.saveAndFlush(v);s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("PRIVATE_TRUST_DRAFT_SAVED",v.id);return state(s);}
 @GetMapping("/api/v1/admin/trust-policy/history") public List<Version> history(@RequestParam(defaultValue="0") int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);List<Version> result=new ArrayList<>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))result.add(view(v));return result;}
 @PostMapping(value="/api/v1/admin/trust-policy/test",consumes="multipart/form-data") @Transactional public State test(@RequestParam Long revision,@RequestParam String versionId,@RequestPart("file") MultipartFile file)throws Exception{
  var s=current(revision);var v=version(versionId);var c=policy.decode(v.configuration);String format=ContentFormats.detect(file);if(file.isEmpty() || file.getSize()>100*1024*1024L || format==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a supported signed sample under 100 MiB");
  Path directory=Files.createTempDirectory("c2pa-trust-test-");Process process=null;
  try{
   Path asset=directory.resolve("sample"+ContentFormats.extension(format)),configuration=directory.resolve("policy.json");file.transferTo(asset);Files.writeString(configuration,policy.workerConfiguration(mapper.writeValueAsString(new TrustPolicy.Snapshot(v.id,c))));
   process=new ProcessBuilder(Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize().toString(),"inspect",asset.toString(),configuration.toString()).redirectOutput(directory.resolve("report.json").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(30,TimeUnit.SECONDS) || process.exitValue()!=0 || Files.size(directory.resolve("report.json"))>8*1024*1024L)throw new IllegalStateException();var report=mapper.readTree(directory.resolve("report.json").toFile());String result=report.path("validation_state").asText();if(result.equals("Invalid") || (c.requireTrustedSigning() && !result.equals("Trusted")))throw new IllegalStateException();v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("PRIVATE_TRUST_SAMPLE_TESTED",v.id);return state(s);
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Sample did not satisfy this trust policy; check credentials, anchors and worker readiness");}
  finally{if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}try(var files=Files.list(directory)){for(var f:files.toList())Files.deleteIfExists(f);}Files.deleteIfExists(directory);}
 }
 @PostMapping("/api/v1/admin/trust-policy/activate") @Transactional public State activate(@RequestBody Selection selection)throws Exception{if(!selection.acknowledgePrivatePolicy())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge private policy does not establish public C2PA trust");var s=current(selection.revision());var v=version(selection.versionId());if(v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this policy with a signed sample first");s.activeVersion=v.id;s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("PRIVATE_TRUST_POLICY_ACTIVATED",v.id);return state(s);}
}
