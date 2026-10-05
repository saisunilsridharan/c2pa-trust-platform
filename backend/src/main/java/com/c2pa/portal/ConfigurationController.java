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
 public record State(Settings draft, Settings active, Long revision, boolean signingAvailable) {}
 private final ConfigurationRepository repository; private final ObjectMapper mapper;
 public ConfigurationController(ConfigurationRepository repository,ObjectMapper mapper) {this.repository=repository;this.mapper=mapper;}
 private ConfigurationRecord record() {
  return repository.findById(1L).orElseGet(()->{
   ConfigurationRecord r=new ConfigurationRecord();r.id=1L;
   r.draft=encode(new Settings("My organization","Creator attribution",Set.of("image/jpeg","image/png"),25,true));
   return repository.saveAndFlush(r);
  });
 }
 private String encode(Settings s) {try{return mapper.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException(e);}}
 private Settings decode(String s) {try{return s==null?null:mapper.readValue(s,Settings.class);}catch(Exception e){throw new IllegalStateException(e);}}
 private State state(ConfigurationRecord r){return new State(decode(r.draft),decode(r.active),r.revision,false);}
 private ConfigurationRecord current(Long revision) {
  ConfigurationRecord r=record();if(!Objects.equals(r.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuration changed; reload before saving");return r;
 }
 @GetMapping("/health") public Map<String,String> health(){return Map.of("status","UP");}
 @GetMapping("/admin/configuration") @SecurityRequirement(name="adminToken") @Transactional
 public State get(){return state(record());}
 @PutMapping("/admin/configuration/draft") @SecurityRequirement(name="adminToken") @Transactional
 public State save(@Valid @RequestBody Update update){ConfigurationRecord r=current(update.revision());r.draft=encode(update.settings());repository.saveAndFlush(r);return state(r);}
 @PostMapping("/admin/configuration/draft/test") @SecurityRequirement(name="adminToken")
 public Map<String,Object> test(@Valid @RequestBody Settings settings){return Map.of("valid",true,"signingAvailable",false,"message","Profile fields are valid. Signing requires a configured key provider and worker integration.");}
 @PostMapping("/admin/configuration/draft/activate") @SecurityRequirement(name="adminToken") @Transactional
 public State activate(@RequestBody Map<String,Long> request){ConfigurationRecord r=current(request.get("revision"));r.active=r.draft;repository.saveAndFlush(r);return state(r);}
}
