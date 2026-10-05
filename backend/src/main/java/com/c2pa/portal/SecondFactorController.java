package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
@RestController @RequestMapping("/api/v1/auth")
public class SecondFactorController {
 private final SecondFactorService factors;private final AccountService accounts;private final AuditService audit;private final UserRepository users;
 public SecondFactorController(SecondFactorService factors,AccountService accounts,AuditService audit,UserRepository users){this.factors=factors;this.accounts=accounts;this.audit=audit;this.users=users;}
 public record Proof(String password,String code){}
 public record Recovery(String username,String recoveryKey,String newPassword,String code){}
 private Long user(HttpServletRequest request){Long id=(Long)request.getAttribute("portal.userId");if(id==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Enroll an account first");return id;}
 private ResponseEntity<?> rejected(){return ResponseEntity.badRequest().body(Map.of("error","Account verification failed"));}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken") @GetMapping("/mfa") public SecondFactorService.Status get(HttpServletRequest request){return factors.status(user(request));}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken") @PostMapping("/mfa/enroll") @Transactional public ResponseEntity<?> enroll(@RequestBody Proof proof,HttpServletRequest request)throws Exception{Long id=user(request);if(!accounts.verifyPassword(id,proof.password()))return rejected();var result=factors.begin(id);audit.record("MFA_ENROLLMENT_STARTED",id.toString());return ResponseEntity.ok(result);}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken") @PostMapping("/mfa/confirm") @Transactional public ResponseEntity<?> confirm(@RequestBody Proof proof,HttpServletRequest request)throws Exception{Long id=user(request);if(!accounts.verifyPassword(id,proof.password()))return rejected();try{var codes=factors.confirm(id,proof.code());if(codes==null){accounts.failedVerification(id);return rejected();}audit.record("MFA_ENABLED",id.toString());return ResponseEntity.ok(Map.of("recoveryCodes",codes,"loginRequired",true));}catch(ResponseStatusException e){if(e.getStatusCode().value()==400)return rejected();throw e;}}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken") @PostMapping("/mfa/disable") @Transactional public ResponseEntity<?> disable(@RequestBody Proof proof,HttpServletRequest request)throws Exception{Long id=user(request);if(!accounts.verifyPassword(id,proof.password()))return rejected();try{if(!factors.disable(id,proof.code())){accounts.failedVerification(id);return rejected();}audit.record("MFA_DISABLED",id.toString());return ResponseEntity.ok(Map.of("loginRequired",true));}catch(ResponseStatusException e){if(e.getStatusCode().value()==400)return rejected();throw e;}}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken") @PostMapping("/recovery-key") @Transactional public ResponseEntity<?> key(@RequestBody Proof proof,HttpServletRequest request)throws Exception{Long id=user(request);if(!accounts.verifyPassword(id,proof.password()))return rejected();if(!factors.verifyLocked(id,proof.code())){accounts.failedVerification(id);return rejected();}String key=factors.createAccountRecovery(id);audit.record("ACCOUNT_RECOVERY_KEY_CREATED",id.toString());return ResponseEntity.ok(Map.of("recoveryKey",key));}
 @PostMapping("/recovery") public ResponseEntity<?> recover(@RequestBody Recovery recovery){if(!accounts.recover(recovery.username(),recovery.recoveryKey(),recovery.newPassword(),recovery.code()))return rejected();var account=users.findByUsername(recovery.username()).orElseThrow();audit.record(0L,account.username,"ACCOUNT_SELF_RECOVERED",account.id.toString());return ResponseEntity.ok(Map.of("loginRequired",true));}
}
