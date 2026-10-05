package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
@Service public class OidcTransactions {
 private final OidcTransactionRepository records;
 public OidcTransactions(OidcTransactionRepository records){this.records=records;}
 @Transactional public OidcTransaction consume(String state,String browser){
  if(state==null || !state.matches("[a-f0-9]{64}") || browser==null || !browser.matches("[a-f0-9]{64}"))throw denied();var record=records.lockById(AccountService.hash(state)).orElseThrow(OidcTransactions::denied);if(record.consumed || !record.expiresAt.isAfter(Instant.now()) || !MessageDigest.isEqual(record.browserHash.getBytes(StandardCharsets.US_ASCII),AccountService.hash(browser).getBytes(StandardCharsets.US_ASCII)))throw denied();record.consumed=true;return records.saveAndFlush(record);
 }
 private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Start a fresh private-provider login");}
}
