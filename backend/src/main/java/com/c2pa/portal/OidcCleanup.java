package com.c2pa.portal;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
@Component public class OidcCleanup {
 private final OidcTransactionRepository transactions;private final CredentialRepository credentials;
 public OidcCleanup(OidcTransactionRepository transactions,CredentialRepository credentials){this.transactions=transactions;this.credentials=credentials;}
 @Scheduled(fixedDelay=60000) @Transactional public void expire(){
  // Grace period allows an in-flight callback to finish before its proof is removed.
  for(var candidate:transactions.findByExpiresAtBefore(Instant.now().minusSeconds(3600),org.springframework.data.domain.PageRequest.of(0,100))){
   var record=transactions.lockById(candidate.id).orElse(null);if(record==null)continue;
   credentials.findById(record.proofCredential).filter(c->c.workspaceId.equals(0L) && c.kind.equals("ACCOUNT_FACTOR")).ifPresent(credentials::delete);
   transactions.delete(record);
  }
 }
}
