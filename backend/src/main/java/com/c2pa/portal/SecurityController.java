package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/admin/security")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class SecurityController {
 private final SecretProtection protection;private final AuditService audit;
 public SecurityController(SecretProtection protection,AuditService audit){this.protection=protection;this.audit=audit;}
 @GetMapping public SecretProtection.Status status(){return protection.status();}
 public record Backup(String password,boolean acknowledgeKeyBackup){}
 @PostMapping("/backup") public ResponseEntity<byte[]> backup(@RequestBody Backup request)throws Exception{if(!request.acknowledgeKeyBackup() || request.password()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge encrypted key backup");byte[] bytes=protection.backup(request.password().toCharArray());audit.record("ENCRYPTION_KEY_BACKED_UP",protection.status().fingerprint());return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"portal-encryption-key.backup\"").body(bytes);}
 @PostMapping(value="/restore",consumes="multipart/form-data") public SecretProtection.Status restore(@RequestPart("file") MultipartFile file,@RequestParam String password,@RequestParam boolean acknowledgeRestore)throws Exception{if(!acknowledgeRestore || file.getSize()>512)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge restoration with a valid key backup");var status=protection.restore(file.getBytes(),password.toCharArray());audit.record("ENCRYPTION_KEY_RESTORED",status.fingerprint());return status;}
}
