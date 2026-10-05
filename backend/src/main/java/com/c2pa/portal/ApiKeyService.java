package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
@Service
public class ApiKeyService {
 private final ApiKeyRepository keys;private final UserRepository users;
 public ApiKeyService(ApiKeyRepository keys,UserRepository users){this.keys=keys;this.users=users;}
 public record Principal(PortalUser user,PortalApiKey key){}
 public Optional<Principal> authenticate(String token){if(token==null || !token.matches("c2pa_key_[a-f0-9]{64}"))return Optional.empty();return keys.findByTokenHash(AccountService.hash(token)).filter(k->!k.revoked && k.expiresAt.isAfter(Instant.now())).flatMap(k->users.findById(k.userId).filter(u->u.enabled && !u.passwordChangeRequired).map(u->new Principal(u,k)));}
 public static boolean permitted(PortalApiKey key,String method,String path){
  Set<String> scopes=Set.of(key.scopes.split(","));
  if(method.equals("GET") && (path.startsWith("/api/v1/jobs") || Set.of("/api/v1/portal/configuration","/api/v1/portal/signing-options","/api/v1/portal/capabilities").contains(path)))return scopes.contains("READ");
  if(method.equals("POST") && path.equals("/api/v1/verification"))return scopes.contains("VERIFY");
  if(method.equals("POST") && (path.equals("/api/v1/signing") || path.equals("/api/v1/jobs") || path.matches("/api/v1/jobs/[a-f0-9-]{36}/retry")))return scopes.contains("SIGN");
  return false;
 }
 public record Issued(PortalApiKey key,String token){}
 @Transactional public Issued issue(Long user,Long workspace,String role,String label,Set<String> scopes,int days){
  if(label==null || label.isBlank() || label.length()>80 || scopes==null || scopes.isEmpty() || !Set.of("READ","VERIFY","SIGN").containsAll(scopes) || (role.equals("VIEWER") && scopes.contains("SIGN")) || days<1 || days>365)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid key label, scopes or lifetime");
  byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String token="c2pa_key_"+HexFormat.of().formatHex(random);Arrays.fill(random,(byte)0);
  PortalApiKey key=new PortalApiKey();key.id=UUID.randomUUID().toString();key.tokenHash=AccountService.hash(token);key.userId=user;key.workspaceId=workspace;key.label=label;key.scopes=String.join(",",new TreeSet<>(scopes));key.createdAt=Instant.now();key.expiresAt=key.createdAt.plusSeconds(days*86400L);keys.saveAndFlush(key);return new Issued(key,token);
 }
}
