package com.c2pa.portal;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
@Service public class RemoteWorkerSecurity {
 private final RemoteWorkerRepository workers;
 public RemoteWorkerSecurity(RemoteWorkerRepository workers){this.workers=workers;}
 static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}catch(Exception e){throw new IllegalStateException(e);}}
 public String issue(RemoteWorker worker,int days){byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token="WKR."+worker.id+"."+HexFormat.of().formatHex(random);worker.tokenHash=hash(token);worker.expiresAt=Instant.now().plusSeconds(days*86400L);return token;}
 public Optional<RemoteWorker> authenticate(String token){
  if(token==null || !token.matches("WKR\\.[0-9a-f-]{36}\\.[0-9a-f]{64}"))return Optional.empty();
  var worker=workers.findById(token.substring(4,40));
  return worker.filter(w->w.expiresAt.isAfter(Instant.now()) && MessageDigest.isEqual(w.tokenHash.getBytes(StandardCharsets.US_ASCII),hash(token).getBytes(StandardCharsets.US_ASCII)));
 }
}
