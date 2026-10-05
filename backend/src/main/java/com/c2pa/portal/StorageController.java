package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
@RestController
@RequestMapping("/api/v1/admin/storage")
public class StorageController {
 private final StorageRepository storage;private final AuditService audit;
 public StorageController(StorageRepository storage,AuditService audit){this.storage=storage;this.audit=audit;}
 public record Settings(Long revision,String provider,int retentionDays){}
 public record Update(@NotNull Long revision,@Min(1) @Max(3650) int retentionDays,boolean acknowledgeDeletion){}
 private AssetStorage settings(){return storage.findById(WorkspaceContext.id()).orElseGet(()->{var settings=new AssetStorage();settings.id=WorkspaceContext.id();return storage.saveAndFlush(settings);});}
 @GetMapping @Transactional public Settings get(){var s=settings();return new Settings(s.revision,s.activeVersion==null?"Managed local storage":"Versioned storage; see Private object storage",s.retentionDays);}
 @PutMapping @Transactional public Settings update(@Valid @RequestBody Update request){
  if(!request.acknowledgeDeletion())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge automatic deletion after retention");
  var s=settings();if(!s.revision.equals(request.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Storage settings changed");s.retentionDays=request.retentionDays();storage.saveAndFlush(s);audit.record("STORAGE_RETENTION_CHANGED",String.valueOf(s.retentionDays));return new Settings(s.revision,s.activeVersion==null?"Managed local storage":"Versioned storage; see Private object storage",s.retentionDays);
 }
}
