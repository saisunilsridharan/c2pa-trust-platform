package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SecretProtectionTest {
 @TempDir Path directory;
 @Test void secretsAreBoundToWorkspaceAndReferenceAndKeyRecoveryWorks()throws Exception {
  var records=mock(CredentialRepository.class);when(records.count()).thenReturn(0L);when(records.fingerprints()).thenReturn(List.of());Path key=directory.resolve("private/key");var protection=new SecretProtection(records,key);
  byte[] value="private-service-token".getBytes(java.nio.charset.StandardCharsets.UTF_8);var sealed=protection.encrypt(1L,"first",value);assertFalse(sealed.value().contains("private-service-token"));assertArrayEquals(value,protection.decrypt(1L,"first",sealed.value(),sealed.keyFingerprint()));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->protection.decrypt(2L,"first",sealed.value(),sealed.keyFingerprint()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->protection.decrypt(1L,"other",sealed.value(),sealed.keyFingerprint()));
  byte[] altered=Base64.getUrlDecoder().decode(sealed.value().substring(3));altered[altered.length-1]^=1;String changed="v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(altered);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->protection.decrypt(1L,"first",changed,sealed.keyFingerprint()));
  when(records.count()).thenReturn(1L);when(records.fingerprints()).thenReturn(List.of(sealed.keyFingerprint()));char[] password="long-backup-password".toCharArray();byte[] backup=protection.backup(password);assertArrayEquals(new char[password.length],password);assertEquals(84,backup.length);
  byte[] oldKey=Files.readAllBytes(key);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->protection.restore(backup.clone(),"wrong-backup-password".toCharArray()));assertArrayEquals(oldKey,Files.readAllBytes(key));
  Files.delete(key);assertEquals("RESTORE_REQUIRED",protection.status().state());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->protection.encrypt(1L,"new",value));assertFalse(Files.exists(key));
  assertTrue(protection.restore(backup.clone(),"long-backup-password".toCharArray()).available());assertArrayEquals(value,new SecretProtection(records,key).decrypt(1L,"first",sealed.value(),sealed.keyFingerprint()));assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(key));
 }
 @Test void separateProcessesCannotOverwriteTheInitialKey()throws Exception {
  var records=mock(CredentialRepository.class);when(records.count()).thenReturn(0L);when(records.fingerprints()).thenReturn(List.of());Path key=directory.resolve("shared/key");var first=new SecretProtection(records,key);var second=new SecretProtection(records,key);
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var start=new java.util.concurrent.CountDownLatch(1);var a=executor.submit(()->{start.await();return first.encrypt(1L,"a",new byte[]{1});});var b=executor.submit(()->{start.await();return second.encrypt(2L,"b",new byte[]{2});});start.countDown();var one=a.get();var two=b.get();assertEquals(one.keyFingerprint(),two.keyFingerprint());assertArrayEquals(new byte[]{1},second.decrypt(1L,"a",one.value(),one.keyFingerprint()));assertArrayEquals(new byte[]{2},first.decrypt(2L,"b",two.value(),two.keyFingerprint()));
  }
 }
}
