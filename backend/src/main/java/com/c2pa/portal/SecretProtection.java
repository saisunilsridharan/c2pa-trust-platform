package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
@Service
public class SecretProtection {
 private final CredentialRepository records;private final Path keyFile;private final SecureRandom random=new SecureRandom();
 @org.springframework.beans.factory.annotation.Autowired
 public SecretProtection(CredentialRepository records){this(records,Path.of(".local/credential-key"));}
 SecretProtection(CredentialRepository records,Path keyFile){this.records=records;this.keyFile=keyFile.toAbsolutePath();}
 public record Status(boolean configured,boolean available,String state,String fingerprint,long encryptedCredentials){}
 private String fingerprint(byte[] key)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));}
 private byte[] readKey()throws Exception{if(!Files.isRegularFile(keyFile,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException();if(Files.size(keyFile)!=32)throw new IllegalStateException();byte[] key=Files.readAllBytes(keyFile);if(key.length!=32){Arrays.fill(key,(byte)0);throw new IllegalStateException();}return key;}
 public synchronized Status status(){long count=records.count();if(!Files.exists(keyFile))return new Status(false,false,count==0?"NOT_INITIALIZED":"RESTORE_REQUIRED",null,count);byte[] key=null;try{key=readKey();String id=fingerprint(key);boolean available=records.fingerprints().stream().allMatch(id::equals);return new Status(true,available,available?"READY":"RESTORE_REQUIRED",id,count);}catch(Exception e){return new Status(true,false,"RESTORE_REQUIRED",null,count);}finally{if(key!=null)Arrays.fill(key,(byte)0);}}
 private void publish(byte[] key)throws Exception{publish(key,true);}
 private void publish(byte[] key,boolean replace)throws Exception{
  Files.createDirectories(keyFile.getParent());Files.setPosixFilePermissions(keyFile.getParent(),PosixFilePermissions.fromString("rwx------"));Path temporary=Files.createTempFile(keyFile.getParent(),"credential-key-",".tmp");
  try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));Files.write(temporary,key);try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
   if(replace)Files.move(temporary,keyFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else {try{Files.createLink(keyFile,temporary);}catch(FileAlreadyExistsException ignored){}}}finally{Files.deleteIfExists(temporary);}
 }
 private byte[] material()throws Exception{
  if(!Files.exists(keyFile)){if(records.count()>0)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Restore the encryption key in Security administration");byte[] key=new byte[32];random.nextBytes(key);try{publish(key,false);}finally{Arrays.fill(key,(byte)0);}}
  try{return readKey();}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Restore the encryption key in Security administration");}
 }
 public record Sealed(String value,String keyFingerprint){}
 private byte[] aad(Long workspace,String id){return ("c2pa-credential-v1:"+workspace+":"+id).getBytes(StandardCharsets.UTF_8);}
 public synchronized Sealed encrypt(Long workspace,String id,byte[] value)throws Exception{
  if(!records.fingerprints().isEmpty() && !status().available())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Restore the original encryption key first");byte[] key=material(),nonce=new byte[12];random.nextBytes(nonce);
  try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(workspace,id));byte[] encrypted=cipher.doFinal(value);byte[] envelope=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,envelope,0,12);System.arraycopy(encrypted,0,envelope,12,encrypted.length);return new Sealed("v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(envelope),fingerprint(key));}finally{Arrays.fill(key,(byte)0);}
 }
 public synchronized byte[] decrypt(Long workspace,String id,String sealed,String expectedFingerprint)throws Exception{
  byte[] key=material();try{
   if(!fingerprint(key).equals(expectedFingerprint) || !sealed.startsWith("v1."))throw new IllegalStateException();byte[] bytes=Base64.getUrlDecoder().decode(sealed.substring(3));if(bytes.length<28)throw new IllegalStateException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,bytes,0,12));cipher.updateAAD(aad(workspace,id));return cipher.doFinal(bytes,12,bytes.length-12);
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Credential is unavailable or failed integrity checks");}finally{Arrays.fill(key,(byte)0);}
 }
 private SecretKeySpec backupKey(char[] password,byte[] salt)throws Exception{
  if(password.length<12 || password.length>128)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Backup password requires 12–128 characters");var spec=new PBEKeySpec(password,salt,600000,256);byte[] bytes=null;try{bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return new SecretKeySpec(bytes,"AES");}finally{spec.clearPassword();if(bytes!=null)Arrays.fill(bytes,(byte)0);}
 }
 public synchronized byte[] backup(char[] password)throws Exception{byte[] key=null;try{if(!status().available())throw new ResponseStatusException(HttpStatus.CONFLICT,"Initialize or restore the encryption key first");key=material();byte[] salt=new byte[16],nonce=new byte[12];random.nextBytes(salt);random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,backupKey(password,salt),new GCMParameterSpec(128,nonce));cipher.updateAAD("C2PAKEY1".getBytes(StandardCharsets.US_ASCII));byte[] encrypted=cipher.doFinal(key);var out=java.nio.ByteBuffer.allocate(8+16+12+encrypted.length);out.put("C2PAKEY1".getBytes(StandardCharsets.US_ASCII)).put(salt).put(nonce).put(encrypted);return out.array();}finally{Arrays.fill(password,'\0');if(key!=null)Arrays.fill(key,(byte)0);}}
 public synchronized Status restore(byte[] backup,char[] password)throws Exception{byte[] key=null;try{
  if(backup.length!=84 || !Arrays.equals(Arrays.copyOfRange(backup,0,8),"C2PAKEY1".getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,backupKey(password,Arrays.copyOfRange(backup,8,24)),new GCMParameterSpec(128,backup,24,12));cipher.updateAAD("C2PAKEY1".getBytes(StandardCharsets.US_ASCII));key=cipher.doFinal(backup,36,48);String id=fingerprint(key);if(!records.fingerprints().stream().allMatch(id::equals))throw new IllegalArgumentException();if(records.count()==0 && Files.exists(keyFile)){byte[] current=readKey();try{if(!fingerprint(current).equals(id))throw new IllegalArgumentException();}finally{Arrays.fill(current,(byte)0);}}publish(key);return status();
 }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Backup/password is invalid or the key does not match stored credentials");}finally{Arrays.fill(password,'\0');Arrays.fill(backup,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);}}
}
