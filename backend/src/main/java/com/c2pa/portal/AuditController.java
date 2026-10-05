package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
@RestController
@SecurityRequirement(name="adminToken")
public class AuditController {
    private final AuditRepository repository;
    public AuditController(AuditRepository repository){this.repository=repository;}
    @GetMapping("/api/v1/admin/audit-events")
    public List<AuditEvent> list(@RequestParam(defaultValue="0") int page){
        if(page<0 || page>10000)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid page");
        return repository.findByWorkspaceIdOrderByIdDesc(WorkspaceContext.id(),PageRequest.of(page,50));
    }
}
