package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class DevelopmentIdentityTest {
 @TempDir Path directory;
 @Test void workspacesUseSeparateKeysAndLegacyDefaultMaterial()throws Exception {
  DevelopmentIdentity identity=new DevelopmentIdentity(directory,Clock.systemUTC(),true);
  var request=new org.springframework.mock.web.MockHttpServletRequest();org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
  try{
   request.setAttribute("portal.workspaceId",1L);identity.create();var first=identity.material();
   request.setAttribute("portal.workspaceId",2L);assertFalse(identity.available());assertThrows(org.springframework.web.server.ResponseStatusException.class,identity::material);identity.create();var second=identity.material();assertNotEquals(first.key(),second.key());assertNotEquals(first.fingerprint(),second.fingerprint());
   identity.rotate(second.fingerprint());request.setAttribute("portal.workspaceId",1L);assertEquals(first.fingerprint(),identity.status().fingerprint());assertEquals(first.key(),identity.material().key());
  }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
 }
 @Test void privateBundleRunsRealSigningProbeAndSurvivesRestart()throws Exception {
  org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("../c2pa-worker/target/debug/c2pa-worker")),"Build the Rust worker before this integration check");
  DevelopmentIdentity identity=new DevelopmentIdentity(directory);identity.create();var material=identity.material();
  String pem=Files.readString(material.key()).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
  var key=java.security.KeyFactory.getInstance("EC").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(pem)));
  java.security.cert.Certificate[] chain;try(var in=Files.newInputStream(material.certificate())){chain=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(in).toArray(java.security.cert.Certificate[]::new);}
  char[] password="test-private-bundle".toCharArray();var store=java.security.KeyStore.getInstance("PKCS12");store.load(null,password);store.setKeyEntry("signer",key,password,chain);var output=new java.io.ByteArrayOutputStream();store.store(output,password);
  var status=identity.importPrivate(output.toByteArray(),password,identity.status().fingerprint());assertTrue(status.available());assertEquals("private-certificate",status.provider());assertFalse(status.productionTrusted());assertFalse(identity.material().development());
  assertTrue(Files.exists(material.key()));assertEquals("private-certificate",new DevelopmentIdentity(directory).status().provider());
 }
 @Test void invalidPrivateImportPreservesCurrentIdentityAndClearsSecrets()throws Exception {
  DevelopmentIdentity identity=new DevelopmentIdentity(directory);identity.create();var current=identity.status();byte[] invalid={1,2,3};char[] password="secret-password".toCharArray();
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->identity.importPrivate(invalid,password,current.fingerprint()));
  assertEquals(current.fingerprint(),identity.status().fingerprint());assertArrayEquals(new byte[3],invalid);assertArrayEquals(new char[15],password);
 }
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
