package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
@Service
public class CredentialService {
 private final CredentialRepository records;private final SecretProtection protection;private final WorkspaceRepository workspaces;
 public CredentialService(CredentialRepository records,SecretProtection protection,WorkspaceRepository workspaces){this.records=records;this.protection=protection;this.workspaces=workspaces;}
 @Transactional public EncryptedCredential create(Long workspace,String label,String value)throws Exception{
  if(label==null || label.isBlank() || label.length()>80 || value==null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length>24000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid credential label/value");
  workspaces.lockById(WorkspaceContext.DEFAULT).orElseThrow();
  EncryptedCredential record=new EncryptedCredential();record.id=UUID.randomUUID().toString();record.workspaceId=workspace;record.label=label;record.createdAt=Instant.now();byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
  try{var sealed=protection.encrypt(workspace,record.id,bytes);record.sealed=sealed.value();record.keyFingerprint=sealed.keyFingerprint();return records.saveAndFlush(record);}finally{Arrays.fill(bytes,(byte)0);}
 }
 public byte[] read(Long workspace,String id)throws Exception{var record=records.findById(id).filter(r->r.workspaceId.equals(workspace)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Credential not found"));return protection.decrypt(workspace,id,record.sealed,record.keyFingerprint);}
}
