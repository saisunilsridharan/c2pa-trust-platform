package com.c2pa.portal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AccountsController {
 private final AccountService accounts;private final UserRepository users;private final SessionRepository sessions;private final AuditService audit;
 public AccountsController(AccountService accounts,UserRepository users,SessionRepository sessions,AuditService audit){this.accounts=accounts;this.users=users;this.sessions=sessions;this.audit=audit;}
 public record Credentials(@NotBlank @Size(max=40) String username,String password){}
 public record NewUser(@NotBlank String username,String password,@Pattern(regexp="ADMIN|SIGNER|VIEWER") @NotNull String role){}
 public record Access(@Pattern(regexp="ADMIN|SIGNER|VIEWER") @NotNull String role,boolean enabled){}
 @GetMapping("/auth/status") public Map<String,Boolean> status(){return Map.of("enrolled",accounts.enrolled());}
 @PostMapping("/auth/login") public AccountService.Login login(@Valid @RequestBody Credentials credentials,HttpServletRequest request){
  var result=accounts.login(credentials.username(),credentials.password());
  if(result==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials or temporarily locked account");
  request.setAttribute("portal.actor",result.user().username());audit.record("USER_LOGIN",String.valueOf(result.user().id()));return result;
 }
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PostMapping("/auth/enroll") public synchronized AccountService.Profile enroll(@Valid @RequestBody Credentials credentials){
  if(accounts.enrolled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrator already enrolled");
  var user=accounts.create(credentials.username(),credentials.password(),"ADMIN");audit.record("ADMINISTRATOR_ENROLLED",String.valueOf(user.id));return accounts.profile(user);
 }
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @GetMapping("/auth/me") public AccountService.Profile me(HttpServletRequest request){
  Long id=(Long)request.getAttribute("portal.userId");
  return id==null?new AccountService.Profile(null,"bootstrap-administrator","ADMIN",true,false):accounts.profile(users.findById(id).orElseThrow());
 }
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PostMapping("/auth/logout") public Map<String,Boolean> logout(HttpServletRequest request){accounts.logout((String)request.getAttribute("portal.sessionToken"));audit.record("USER_LOGOUT",String.valueOf(request.getAttribute("portal.userId")));return Map.of("loggedOut",true);}
 public record PasswordChange(String currentPassword,String newPassword){}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PostMapping("/auth/password") public Map<String,Boolean> password(@RequestBody PasswordChange change,HttpServletRequest request){
  Long id=(Long)request.getAttribute("portal.userId");
  if(id==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Enroll an administrator first");
  if(!accounts.changePassword(id,change.currentPassword(),change.newPassword()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Current password is incorrect");
  audit.record("PASSWORD_CHANGED",String.valueOf(id));return Map.of("loginRequired",true);
 }
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @GetMapping("/admin/users") public List<AccountService.Profile> list(){return users.findAll().stream().map(accounts::profile).toList();}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PostMapping("/admin/users") public AccountService.Profile create(@Valid @RequestBody NewUser request){if(!accounts.enrolled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Enroll the first administrator before creating users");var user=accounts.create(request.username(),request.password(),request.role());audit.record("USER_CREATED",String.valueOf(user.id));return accounts.profile(user);}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PutMapping("/admin/users/{id}") @Transactional public AccountService.Profile update(@PathVariable Long id,@Valid @RequestBody Access request){
  var administrators=users.lockAdministrators();
  var user=users.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found"));
  if(user.enabled && user.role.equals("ADMIN") && (!request.enabled() || !request.role().equals("ADMIN")) && administrators.size()<=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Keep at least one enabled administrator");
  user.role=request.role();user.enabled=request.enabled();users.save(user);if(!user.enabled)sessions.deleteAllByUserId(user.id);audit.record("USER_ACCESS_CHANGED",String.valueOf(user.id));return accounts.profile(user);
 }
 public record Reset(String temporaryPassword,boolean acknowledgeSessionRevocation){}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PostMapping("/admin/users/{id}/password-reset") public Map<String,Boolean> reset(@PathVariable Long id,@RequestBody Reset request,HttpServletRequest servlet){
  if(id.equals(servlet.getAttribute("portal.userId")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use your own password-change screen");
  if(!request.acknowledgeSessionRevocation())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge password reset and session revocation");
  accounts.resetPassword(id,request.temporaryPassword());audit.record("PASSWORD_RESET_REQUIRED",String.valueOf(id));return Map.of("passwordChangeRequired",true);
 }
 public record SessionCount(Long userId,long activeSessions){}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @GetMapping("/admin/sessions") public List<SessionCount> sessions(){return users.findAll().stream().map(u->new SessionCount(u.id,sessions.countByUserIdAndExpiresAtAfter(u.id,java.time.Instant.now()))).toList();}
 @io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
 @PostMapping("/admin/users/{id}/revoke-sessions") @Transactional public Map<String,Boolean> revoke(@PathVariable Long id){
  if(users.lockById(id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");sessions.deleteAllByUserId(id);audit.record("USER_SESSIONS_REVOKED",String.valueOf(id));return Map.of("revoked",true);
 }

}
