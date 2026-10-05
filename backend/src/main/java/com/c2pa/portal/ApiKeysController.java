package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.Instant;
@RestController
@RequestMapping("/api/v1/auth/api-keys")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class ApiKeysController {
 private final ApiKeyRepository keys;private final ApiKeyService service;private final AuditService audit;
 public ApiKeysController(ApiKeyRepository keys,ApiKeyService service,AuditService audit){this.keys=keys;this.service=service;this.audit=audit;}
 public record Key(String id,String label,Set<String> scopes,Instant createdAt,Instant expiresAt,boolean revoked){}
 public record Creation(String label,Set<String> scopes,int days){}
 public record Created(Key key,String token){}
 private Key view(PortalApiKey key){return new Key(key.id,key.label,Set.of(key.scopes.split(",")),key.createdAt,key.expiresAt,key.revoked);}
 private Long user(HttpServletRequest request){var id=(Long)request.getAttribute("portal.userId");if(id==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Enroll an account first");return id;}
 @GetMapping public List<Key> list(@RequestParam(defaultValue="0") int page,HttpServletRequest request){if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return keys.findByWorkspaceIdAndUserIdOrderByCreatedAtDesc(WorkspaceContext.id(),user(request),org.springframework.data.domain.PageRequest.of(page,50)).stream().map(this::view).toList();}
 @PostMapping public Created create(@RequestBody Creation creation,HttpServletRequest request){var issued=service.issue(user(request),WorkspaceContext.id(),(String)request.getAttribute("portal.role"),creation.label(),creation.scopes(),creation.days());audit.record("API_KEY_CREATED",issued.key().id);return new Created(view(issued.key()),issued.token());}
 @DeleteMapping("/{id}") @Transactional public Map<String,Boolean> revoke(@PathVariable String id,HttpServletRequest request){Long owner=user(request);var key=keys.findById(id).filter(k->k.userId.equals(owner) && k.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));key.revoked=true;keys.saveAndFlush(key);audit.record("API_KEY_REVOKED",key.id);return Map.of("revoked",true);}
}
