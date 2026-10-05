package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Objects;
@RestController @RequestMapping("/api/v1/admin/processing")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class ProcessingController {
 private final ProcessingRepository settings;private final AuditService audit;
 public ProcessingController(ProcessingRepository settings,AuditService audit){this.settings=settings;this.audit=audit;}
 public record Settings(Long revision,int workerTimeoutSeconds,int maxAttempts){}
 public record Update(@NotNull Long revision,@Min(10) @Max(120) int workerTimeoutSeconds,@Min(1) @Max(100) int maxAttempts){}
 private ProcessingSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new ProcessingSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});}
 private Settings view(ProcessingSettings s){return new Settings(s.revision,s.workerTimeoutSeconds,s.maxAttempts);}
 @GetMapping @Transactional public Settings get(){return view(settings());}
 @PutMapping @Transactional public Settings save(@Valid @RequestBody Update update){var s=settings();if(!Objects.equals(s.revision,update.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Processing settings changed");s.workerTimeoutSeconds=update.workerTimeoutSeconds();s.maxAttempts=update.maxAttempts();settings.saveAndFlush(s);audit.record("PROCESSING_LIMITS_CHANGED",String.valueOf(s.revision));return view(s);}
}
