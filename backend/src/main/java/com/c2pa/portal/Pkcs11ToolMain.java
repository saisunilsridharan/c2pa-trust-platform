package com.c2pa.portal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Isolated native-provider process. Secrets arrive on stdin, never command arguments. */
public final class Pkcs11ToolMain {
 public static void main(String[] args){
  Path config=null;char[] pin=null;
  try{
   byte[] bytes=System.in.readNBytes(131073);if(bytes.length>131072)throw new IllegalArgumentException();
   var mapper=new ObjectMapper();var input=mapper.readTree(bytes);Arrays.fill(bytes,(byte)0);
   String module=input.path("module").asText(),alias=input.path("alias").asText();int slot=input.path("slotListIndex").asInt(-1);
   if(!Path.of(module).isAbsolute() || module.contains("\n") || module.contains("\r") || module.contains("\"") || slot<0 || slot>128 || alias.isBlank() || alias.length()>200)throw new IllegalArgumentException();
   config=Files.createTempFile("c2pa-pkcs11-",".cfg");Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
   Files.writeString(config,"name=C2PA\nlibrary="+module+"\nslotListIndex="+slot+"\n");
   var provider=Security.getProvider("SunPKCS11").configure(config.toString());
   pin=input.path("pin").asText().toCharArray();var store=KeyStore.getInstance("PKCS11",provider);store.load(null,pin);Arrays.fill(pin,'\0');
   var key=store.getKey(alias,null);if(!(key instanceof PrivateKey privateKey) || key.getEncoded()!=null)throw new IllegalArgumentException();
   byte[] data=Base64.getDecoder().decode(input.path("data").asText());if(data.length==0 || data.length>65536)throw new IllegalArgumentException();
   var signature=Signature.getInstance("SHA256withECDSA",provider);signature.initSign(privateKey);signature.update(data);byte[] signed=signature.sign();
   System.out.print(mapper.writeValueAsString(Map.of("signature",Base64.getEncoder().encodeToString(signed),"keyExportable",false)));
  }catch(Throwable e){System.err.print("PKCS#11 operation failed");System.exit(1);}
  finally{if(pin!=null)Arrays.fill(pin,'\0');if(config!=null)try{Files.deleteIfExists(config);}catch(Exception ignored){}}
 }
}
