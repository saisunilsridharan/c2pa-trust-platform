package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController
@RequestMapping("/api/v1/admin/credentials")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class CredentialsController {
 private final CredentialRepository records;private final CredentialService service;private final AuditService audit;
 public CredentialsController(CredentialRepository records,CredentialService service,AuditService audit){this.records=records;this.service=service;this.audit=audit;}
 public record Metadata(String id,String label,java.time.Instant createdAt){}
 public record Creation(@NotBlank @Size(max=80) String label,@NotBlank @Size(max=24000) String value){}
 @GetMapping public List<Metadata> list(@RequestParam(defaultValue="0") int page){if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return records.findByWorkspaceIdAndKindOrderByCreatedAtDesc(WorkspaceContext.id(),"INTEGRATION",PageRequest.of(page,50)).stream().map(r->new Metadata(r.id,r.label,r.createdAt)).toList();}
 @PostMapping public Metadata create(@Valid @RequestBody Creation request)throws Exception{var record=service.create(WorkspaceContext.id(),request.label(),request.value());audit.record("ENCRYPTED_CREDENTIAL_CREATED",record.id);return new Metadata(record.id,record.label,record.createdAt);}
}
