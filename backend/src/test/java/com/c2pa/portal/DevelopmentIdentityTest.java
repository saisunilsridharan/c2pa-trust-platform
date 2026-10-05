package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class DevelopmentIdentityTest {
 @TempDir Path directory;
 @Test void rotationPreservesInFlightMaterialAndRejectsStaleChanges() throws Exception {
  DevelopmentIdentity identity=new DevelopmentIdentity(directory);
  assertFalse(identity.status().configured());identity.create();
  var first=identity.status();assertTrue(first.available());assertFalse(first.productionTrusted());
  var material=identity.material();byte[] oldKey=Files.readAllBytes(material.key());
  identity.create();assertEquals(first.fingerprint(),identity.status().fingerprint());
  identity.rotate(first.fingerprint());var second=identity.status();
  assertTrue(second.available());assertNotEquals(first.fingerprint(),second.fingerprint());
  assertArrayEquals(oldKey,Files.readAllBytes(material.key()));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->identity.rotate(first.fingerprint()));
  var expired=new DevelopmentIdentity(directory,Clock.fixed(second.expiresAt().plusSeconds(1),ZoneOffset.UTC));
  assertEquals("EXPIRED",expired.status().state());assertFalse(expired.available());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,expired::material);
  var restarted=new DevelopmentIdentity(directory);assertEquals(second.fingerprint(),restarted.status().fingerprint());
  assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(restarted.material().key()));
 }
}
