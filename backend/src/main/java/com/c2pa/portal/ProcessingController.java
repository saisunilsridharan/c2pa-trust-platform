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
 private final WorkerSandbox sandbox;private final ProcessingRepository settings;private final AuditService audit;
 public ProcessingController(ProcessingRepository settings,AuditService audit,WorkerSandbox sandbox){this.sandbox=sandbox;this.settings=settings;this.audit=audit;}
 public record Settings(Long revision,int workerTimeoutSeconds,int maxAttempts,int maxMemoryMb,int maxCpuSeconds,String sandboxMode){}
 public record Update(@NotNull Long revision,@Min(10) @Max(120) int workerTimeoutSeconds,@Min(1) @Max(100) int maxAttempts,@Min(256) @Max(4096) Integer maxMemoryMb,@Min(10) @Max(120) Integer maxCpuSeconds,@Pattern(regexp="LIMITED|NAMESPACE") String sandboxMode){}
 private ProcessingSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new ProcessingSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});}
 private Settings view(ProcessingSettings s){return new Settings(s.revision,s.workerTimeoutSeconds,s.maxAttempts,s.maxMemoryMb,s.maxCpuSeconds,s.sandboxMode);}
 @GetMapping @Transactional public Settings get(){return view(settings());}
 @PutMapping @Transactional public Settings save(@Valid @RequestBody Update update){var s=settings();if(!Objects.equals(s.revision,update.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Processing settings changed");if("NAMESPACE".equals(update.sandboxMode()) && !sandbox.namespaceAvailable())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Namespace isolation is unavailable on this worker host; configure the deployment runtime first");s.workerTimeoutSeconds=update.workerTimeoutSeconds();s.maxAttempts=update.maxAttempts();if(update.maxMemoryMb()!=null)s.maxMemoryMb=update.maxMemoryMb();if(update.maxCpuSeconds()!=null)s.maxCpuSeconds=update.maxCpuSeconds();if(update.sandboxMode()!=null)s.sandboxMode=update.sandboxMode();settings.saveAndFlush(s);audit.record("PROCESSING_LIMITS_CHANGED",String.valueOf(s.revision));return view(s);}
 @PostMapping("/test-sandbox") public java.util.Map<String,Object> testSandbox(){return sandbox.capabilities();}
}
