package com.c2pa.portal;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public final class AuditHasher {
 public static final String GENESIS="0".repeat(64);
 private AuditHasher(){}
 public static String hash(AuditEvent event){try{
  var out=new java.io.ByteArrayOutputStream();try(var data=new java.io.DataOutputStream(out)){
   for(String value:List.of("c2pa-audit-v1",event.workspaceId.toString(),event.chainIndex.toString(),event.previousHash,Objects.toString(event.createdAt,""),Objects.toString(event.actor,""),Objects.toString(event.action,""),Objects.toString(event.reference,""),String.valueOf(event.legacyImported))){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);data.writeInt(bytes.length);data.write(bytes);}
  }return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
 }catch(Exception e){throw new IllegalStateException(e);}}
}
