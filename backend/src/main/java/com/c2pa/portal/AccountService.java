package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
@Service
public class AccountService {
 private final UserRepository users; private final SessionRepository sessions;
 private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
 private final String dummy=encoder.encode("unused-account-password");
 public AccountService(UserRepository users,SessionRepository sessions){this.users=users;this.sessions=sessions;}
 public record Profile(Long id,String username,String role,boolean enabled){}
 public record Login(String token,Instant expiresAt,Profile user){}
 public static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public Profile profile(PortalUser user){return new Profile(user.id,user.username,user.role,user.enabled);}
 public boolean enrolled(){return users.count()>0;}
 public Optional<PortalUser> authenticate(String token){
  if(token==null || !token.matches("[a-f0-9]{64}"))return Optional.empty();
  return sessions.findById(hash(token)).filter(s->s.expiresAt.isAfter(Instant.now())).flatMap(s->users.findById(s.userId)).filter(u->u.enabled);
 }
 @Transactional
 public Login login(String username,String password){
  if(username==null || password==null || password.getBytes(StandardCharsets.UTF_8).length>72)return null;
  var found=users.lockByUsername(username);
  if(found.isEmpty()){encoder.matches(password,dummy);return null;}
  PortalUser user=found.get();
  if(!user.enabled || (user.lockedUntil!=null && user.lockedUntil.isAfter(Instant.now())))return null;
  if(!encoder.matches(password,user.passwordHash)){
   user.failedLogins++;if(user.failedLogins>=5){user.lockedUntil=Instant.now().plusSeconds(900);user.failedLogins=0;}users.save(user);return null;
  }
  user.failedLogins=0;user.lockedUntil=null;users.save(user);
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=HexFormat.of().formatHex(bytes);
  LoginSession session=new LoginSession();session.tokenHash=hash(token);session.userId=user.id;session.expiresAt=Instant.now().plusSeconds(8*3600);sessions.save(session);
  return new Login(token,session.expiresAt,profile(user));
 }
 public void logout(String token){if(token!=null)sessions.deleteById(hash(token));}
 public PortalUser create(String username,String password,String role){
  validatePassword(password);
  if(!username.matches("[a-z][a-z0-9_.-]{2,39}") || !Set.of("ADMIN","SIGNER","VIEWER").contains(role))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid username or role");
  if(users.findByUsername(username).isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");
  PortalUser user=new PortalUser();user.username=username;user.passwordHash=encoder.encode(password);user.role=role;return users.saveAndFlush(user);
 }
 @Transactional
 public boolean changePassword(Long userId,String currentPassword,String newPassword){
  validatePassword(newPassword);
  var user=users.findById(userId).orElseThrow();
  if(currentPassword==null || currentPassword.getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(currentPassword,user.passwordHash))return false;
  user.passwordHash=encoder.encode(newPassword);users.save(user);sessions.deleteAllByUserId(userId);return true;
 }
 static void validatePassword(String password){
  if(password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password requires at least 12 characters and at most 72 UTF-8 bytes");
 }
}
