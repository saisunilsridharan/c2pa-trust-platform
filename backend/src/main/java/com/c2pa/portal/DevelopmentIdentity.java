package com.c2pa.portal;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.security.cert.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class DevelopmentIdentity {
 private final Path storage;
 private final Path legacy;
 private final java.time.Clock clock;
 public DevelopmentIdentity(){this(Path.of(".local"));}
 DevelopmentIdentity(Path base){this(base,java.time.Clock.systemUTC());}
 DevelopmentIdentity(Path base,java.time.Clock clock){this.clock=clock;storage=base.resolve("development-identities").toAbsolutePath();legacy=base.resolve("development-identity").toAbsolutePath();}
 public record Status(boolean configured,boolean available,String state,String provider,boolean productionTrusted,String fingerprint,Instant expiresAt) {}
 public record Material(Path certificate,Path key,String fingerprint) {}
 private Path directory() throws Exception {
  Path pointer=storage.resolve("current");
  if(!Files.exists(pointer))return legacy;
  String id=Files.readString(pointer).trim();
  UUID.fromString(id);
  return storage.resolve(id);
 }
 public synchronized Status status(){
  try {
   Path directory=directory();
   if(!Files.exists(directory))return new Status(false,false,"NOT_CONFIGURED","development",false,null,null);
   X509Certificate certificate;
   try(var input=Files.newInputStream(directory.resolve("chain.pem"))){certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);}
   String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
   String state="VALID";
   try{certificate.checkValidity(Date.from(clock.instant()));}catch(CertificateExpiredException e){state="EXPIRED";}catch(CertificateNotYetValidException e){state="NOT_YET_VALID";}
   if(!Files.isRegularFile(directory.resolve("key.pem")))state="KEY_UNAVAILABLE";
   return new Status(true,state.equals("VALID"),state,"development",false,fingerprint,certificate.getNotAfter().toInstant());
  } catch(Exception e){return new Status(true,false,"INVALID","development",false,"unavailable",null);}
 }
 public boolean available(){return status().available();}
 public synchronized Material material() throws Exception {
  Status status=status();
  if(!status.available())throw new ResponseStatusException(HttpStatus.CONFLICT,"Development identity is unavailable or expired; rotate it in the UI");
  Path directory=directory();return new Material(directory.resolve("chain.pem"),directory.resolve("key.pem"),status.fingerprint());
 }
 public record Creation(Status status,boolean created) {}
 public synchronized Creation create() throws Exception {
  if(status().configured())return new Creation(status(),false);
  publish(generate());return new Creation(status(),true);
 }
 public synchronized Status rotate(String expectedFingerprint) throws Exception {
  Status current=status();
  if(!current.configured())throw new ResponseStatusException(HttpStatus.CONFLICT,"Create an identity first");
  if(!Objects.equals(expectedFingerprint,current.fingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Identity changed; reload before rotation");
  publish(generate());return status();
 }
 private Path generate() throws Exception {
  Files.createDirectories(storage);Files.setPosixFilePermissions(storage,PosixFilePermissions.fromString("rwx------"));
  Path temporary=Files.createTempDirectory(storage,"identity-");
  try {
   execute(temporary,"openssl","req","-x509","-newkey","ec","-pkeyopt","ec_paramgen_curve:P-256","-nodes","-keyout","root-key.pem","-out","root.pem","-days","30","-subj","/CN=Portal Development Root","-addext","basicConstraints=critical,CA:TRUE","-addext","keyUsage=critical,keyCertSign,cRLSign");
   execute(temporary,"openssl","req","-new","-newkey","ec","-pkeyopt","ec_paramgen_curve:P-256","-nodes","-keyout","key.pem","-out","request.pem","-subj","/CN=Portal Development Signer");
   Files.writeString(temporary.resolve("extensions.cnf"),"basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=emailProtection,1.3.6.1.4.1.62558.2.1\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n");
   execute(temporary,"openssl","x509","-req","-in","request.pem","-CA","root.pem","-CAkey","root-key.pem","-CAcreateserial","-out","leaf.pem","-days","30","-extfile","extensions.cnf");
   Files.writeString(temporary.resolve("chain.pem"),Files.readString(temporary.resolve("leaf.pem"))+Files.readString(temporary.resolve("root.pem")));
   Files.setPosixFilePermissions(temporary.resolve("key.pem"),PosixFilePermissions.fromString("rw-------"));
   Files.delete(temporary.resolve("root-key.pem"));
   Path version=storage.resolve(UUID.randomUUID().toString());
   Files.move(temporary,version,StandardCopyOption.ATOMIC_MOVE);return version;
  } finally {
   if(Files.exists(temporary)) {
    try(var paths=Files.list(temporary)){for(Path file:paths.toList())Files.deleteIfExists(file);}
    Files.deleteIfExists(temporary);
   }
  }
 }
 private void publish(Path version) throws Exception {
  Path pointer=Files.createTempFile(storage,"current-",".tmp");
  try{Files.writeString(pointer,version.getFileName().toString());Files.move(pointer,storage.resolve("current"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
  finally{Files.deleteIfExists(pointer);}
 }
 private void execute(Path directory,String... command) throws Exception {
  Process process=new ProcessBuilder(command).directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  try {
   if(!process.waitFor(15,TimeUnit.SECONDS) || process.exitValue()!=0)throw new IllegalStateException("Development certificate generation failed");
  } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
 }
}
