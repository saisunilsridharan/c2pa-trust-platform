package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
@Service public class SecondFactorService {
 private final AccountFactorRepository factors;private final CredentialService credentials;private final UserRepository users;private final SessionRepository sessions;private final ApiKeyRepository keys;
 public SecondFactorService(AccountFactorRepository factors,CredentialService credentials,UserRepository users,SessionRepository sessions,ApiKeyRepository keys){this.factors=factors;this.credentials=credentials;this.users=users;this.sessions=sessions;this.keys=keys;}
 public record Status(boolean enabled,boolean pending,int recoveryCodesRemaining,boolean accountRecoveryConfigured){}
 public record Enrollment(String secret,String uri,Instant expiresAt){}
 private AccountFactor factor(Long user){return factors.findById(user).orElseGet(()->{var f=new AccountFactor();f.id=user;return factors.saveAndFlush(f);});}
 public Status status(Long user){var f=factors.findById(user).orElse(null);return f==null?new Status(false,false,0,false):new Status(f.enabled,f.pendingCredential!=null && f.pendingExpiresAt.isAfter(Instant.now()),hashes(f).size(),f.accountRecoveryHash!=null);}
 private List<String> hashes(AccountFactor factor){return factor.recoveryHashes==null || factor.recoveryHashes.isEmpty()?new ArrayList<>():new ArrayList<>(List.of(factor.recoveryHashes.split(",")));}
 private static boolean equal(String a,String b){return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
 private long step(AccountFactor factor,String credential,String supplied,boolean replayProtection)throws Exception {
  if(supplied==null || !supplied.matches("[0-9]{6}"))return -1;byte[] encoded=credentials.readProtected(credential),secret=null;
  try{secret=Base64.getDecoder().decode(encoded);long now=Instant.now().getEpochSecond()/30;long accepted=-1;for(long at=now-1;at<=now+1;at++){boolean match=equal(Totp.code(secret,at),supplied);if(match && (!replayProtection || at>factor.lastStep))accepted=at;}return accepted;}finally{Arrays.fill(encoded,(byte)0);if(secret!=null)Arrays.fill(secret,(byte)0);}
 }
 /** Caller holds the user row lock, serializing TOTP replay and recovery-code consumption. */
 public boolean verifyLocked(Long user,String code)throws Exception {
  var found=factors.findById(user);if(found.isEmpty() || !found.get().enabled)return true;var f=found.get();long accepted=step(f,f.activeCredential,code,true);
  if(accepted>=0){f.lastStep=accepted;factors.saveAndFlush(f);return true;}
  if(code==null || !code.matches("[a-f0-9]{32}"))return false;String digest=AccountService.hash(code);var hashes=hashes(f);String matched=null;for(String candidate:hashes)if(equal(candidate,digest))matched=candidate;if(matched==null)return false;hashes.remove(matched);f.recoveryHashes=String.join(",",hashes);factors.saveAndFlush(f);return true;
 }
 @Transactional public Enrollment begin(Long user)throws Exception {
  var account=users.lockById(user).orElseThrow();var f=factor(user);if(f.enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Disable the current second factor before enrolling another");byte[] secret=new byte[20];new SecureRandom().nextBytes(secret);try{String encoded=Totp.encode(secret);f.pendingCredential=credentials.createProtected("Account second factor",Base64.getEncoder().encodeToString(secret)).id;f.pendingExpiresAt=Instant.now().plusSeconds(600);factors.saveAndFlush(f);String label=java.net.URLEncoder.encode("C2PA Portal:"+account.username,StandardCharsets.UTF_8).replace("+","%20");return new Enrollment(encoded,"otpauth://totp/"+label+"?secret="+encoded+"&issuer=C2PA%20Portal&algorithm=SHA1&digits=6&period=30",f.pendingExpiresAt);}finally{Arrays.fill(secret,(byte)0);}
 }
 @Transactional public List<String> confirm(Long user,String code)throws Exception {
  users.lockById(user).orElseThrow();var f=factor(user);if(f.enabled || f.pendingCredential==null || !f.pendingExpiresAt.isAfter(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Start a fresh second-factor enrollment");long accepted=step(f,f.pendingCredential,code,false);if(accepted<0)return null;f.enabled=true;f.activeCredential=f.pendingCredential;f.pendingCredential=null;f.pendingExpiresAt=null;f.lastStep=accepted;List<String> codes=generate(8);f.recoveryHashes=String.join(",",codes.stream().map(AccountService::hash).toList());factors.saveAndFlush(f);revoke(user);return codes;
 }
 @Transactional public boolean disable(Long user,String code)throws Exception {users.lockById(user).orElseThrow();if(!verifyLocked(user,code))return false;var f=factor(user);f.enabled=false;f.activeCredential=null;f.pendingCredential=null;f.pendingExpiresAt=null;f.recoveryHashes=null;f.lastStep=-1;factors.saveAndFlush(f);revoke(user);return true;}
 @Transactional public String createAccountRecovery(Long user){users.lockById(user).orElseThrow();var f=factor(user);String token=generate(1).getFirst();f.accountRecoveryHash=AccountService.hash(token);factors.saveAndFlush(f);return token;}
 public boolean consumeAccountRecoveryLocked(Long user,String token){var f=factors.findById(user).orElse(null);if(f==null || token==null || !token.matches("[a-f0-9]{32}") || !equal(f.accountRecoveryHash,AccountService.hash(token)))return false;f.accountRecoveryHash=null;factors.saveAndFlush(f);return true;}
 public boolean hasAccountRecoveryLocked(Long user,String token){var f=factors.findById(user).orElse(null);return f!=null && token!=null && token.matches("[a-f0-9]{32}") && equal(f.accountRecoveryHash,AccountService.hash(token));}
 private List<String> generate(int count){List<String> result=new ArrayList<>();for(int i=0;i<count;i++){byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);result.add(HexFormat.of().formatHex(bytes));Arrays.fill(bytes,(byte)0);}return result;}
 private void revoke(Long user){sessions.deleteAllByUserId(user);keys.deleteAllByUserId(user);}
}
