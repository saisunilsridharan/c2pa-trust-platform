package com.c2pa.portal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class ConfigurationController {
 public record Settings(@NotBlank @Size(max=120) String organizationName,
   @NotBlank @Size(max=120) String profileName,
   @NotEmpty Set<@NotNull @Pattern(regexp="image/jpeg|image/png") String> formats,
   @Min(1) @Max(100) int maxUploadMb, boolean requireAiDisclosure) {}
 public record Update(@NotNull @Valid Settings settings, @NotNull Long revision) {}
 public record State(Settings draft, Settings active, Long revision, boolean signingAvailable, Long activeRevision) {}
 private final ConfigurationRepository repository; private final ObjectMapper mapper; private final DevelopmentIdentity identity; private final ConfigurationVersionRepository versions; private final AuditService audit;
 public ConfigurationController(ConfigurationRepository repository,ObjectMapper mapper,DevelopmentIdentity identity,ConfigurationVersionRepository versions,AuditService audit) {this.repository=repository;this.mapper=mapper;this.identity=identity;this.versions=versions;this.audit=audit;}
 private ConfigurationRecord record() {
  ConfigurationRecord record=repository.findById(1L).orElseGet(()->{
   ConfigurationRecord r=new ConfigurationRecord();r.id=1L;
   r.draft=encode(new Settings("My organization","Creator attribution",Set.of("image/jpeg","image/png"),25,true));
   return repository.saveAndFlush(r);
  });
  if(record.active!=null && record.activeRevision==null){record.activeRevision=record.revision;repository.saveAndFlush(record);}
  if(record.active!=null && !versions.existsByConfigurationRevision(record.activeRevision))snapshot(record,"RECOVERED");
  return record;
 }
 private String encode(Settings s) {try{return mapper.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException(e);}}
 private Settings decode(String s) {try{return s==null?null:mapper.readValue(s,Settings.class);}catch(Exception e){throw new IllegalStateException(e);}}
 private State state(ConfigurationRecord r){return new State(decode(r.draft),decode(r.active),r.revision,identity.available(),r.activeRevision);}
 private ConfigurationRecord current(Long revision) {
  ConfigurationRecord r=record();if(!Objects.equals(r.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuration changed; reload before saving");return r;
 }
 @GetMapping("/portal/configuration") @Transactional
 public State publicConfiguration(){ConfigurationRecord r=record();return new State(null,decode(r.active),r.revision,identity.available(),r.activeRevision);}
 @GetMapping("/health") public Map<String,String> health(){return Map.of("status","UP");}
 @GetMapping("/admin/configuration") @SecurityRequirement(name="adminToken") @Transactional
 public State get(){return state(record());}
 @PutMapping("/admin/configuration/draft") @SecurityRequirement(name="adminToken") @Transactional
 public State save(@Valid @RequestBody Update update){ConfigurationRecord r=current(update.revision());r.draft=encode(update.settings());repository.saveAndFlush(r);audit.record("CONFIGURATION_DRAFT_SAVED",String.valueOf(r.revision));return state(r);}
 @PostMapping("/admin/configuration/draft/test") @SecurityRequirement(name="adminToken")
 public Map<String,Object> test(@Valid @RequestBody Settings settings){return Map.of("valid",true,"signingAvailable",identity.available(),"message","Profile fields are valid. Development signing requires a development identity; production trust is unavailable.");}
 @PostMapping("/admin/configuration/draft/activate") @SecurityRequirement(name="adminToken") @Transactional
 public State activate(@RequestBody Map<String,Long> request){ConfigurationRecord r=current(request.get("revision"));r.active=r.draft;r.activeRevision=r.revision+1;repository.saveAndFlush(r);snapshot(r,"ACTIVATED");return state(r);}
 public record Rollback(@NotNull Long versionId,@NotNull Long revision) {}
 public record History(Long id,Long revision,java.time.Instant createdAt,String action,Settings settings) {}
 private void snapshot(ConfigurationRecord record,String action){
  ConfigurationVersion version=new ConfigurationVersion();version.configurationRevision=record.activeRevision;
  version.createdAt=java.time.Instant.now();version.action=action;version.settings=record.active;
  versions.save(version);audit.record("CONFIGURATION_"+action,String.valueOf(record.activeRevision));
 }
 @GetMapping("/admin/configuration/history") @SecurityRequirement(name="adminToken")
 public List<History> history(@RequestParam(defaultValue="0") int page){
  if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");
  return versions.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(page,50)).stream().map(v->new History(v.id,v.configurationRevision,v.createdAt,v.action,decode(v.settings))).toList();
 }
 @PostMapping("/admin/configuration/rollback") @SecurityRequirement(name="adminToken") @Transactional
 public State rollback(@Valid @RequestBody Rollback request){
  ConfigurationRecord record=current(request.revision());
  ConfigurationVersion version=versions.findById(request.versionId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Configuration version not found"));
  record.draft=version.settings;record.active=version.settings;record.activeRevision=record.revision+1;
  repository.saveAndFlush(record);snapshot(record,"ROLLED_BACK");return state(record);
 }
}
