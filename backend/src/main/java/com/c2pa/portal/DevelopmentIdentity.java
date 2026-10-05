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
 private boolean scopedRoot=false;private final Map<Long,DevelopmentIdentity> scopes=new HashMap<>();
 public DevelopmentIdentity(){this(Path.of(".local"));scopedRoot=true;}
 DevelopmentIdentity(Path base){this(base,java.time.Clock.systemUTC());}
 DevelopmentIdentity(Path base,java.time.Clock clock,boolean scopedRoot){this(base,clock);this.scopedRoot=scopedRoot;}
 DevelopmentIdentity(Path base,java.time.Clock clock){this.clock=clock;storage=base.resolve("development-identities").toAbsolutePath();legacy=base.resolve("development-identity").toAbsolutePath();}
 private synchronized DevelopmentIdentity scoped(){Long id=WorkspaceContext.id();if(!scopedRoot || id.equals(1L))return this;return scopes.computeIfAbsent(id,i->new DevelopmentIdentity(storage.getParent().resolve("workspaces").resolve(i.toString()),clock));}
 public record Status(boolean configured,boolean available,String state,String provider,boolean productionTrusted,String fingerprint,Instant expiresAt) {}
 public record Material(Path certificate,Path key,String fingerprint,boolean development) { public Material(Path certificate,Path key,String fingerprint){this(certificate,key,fingerprint,true);} }
 private Path directory() throws Exception {
  Path pointer=storage.resolve("current");
  if(!Files.exists(pointer))return legacy;
  String id=Files.readString(pointer).trim();
  UUID.fromString(id);
  return storage.resolve(id);
 }
 public synchronized Status status(){
  var scope=scoped();if(scope!=this)return scope.status();
  try {
   Path directory=directory();
   if(!Files.exists(directory))return new Status(false,false,"NOT_CONFIGURED","development",false,null,null);
   X509Certificate certificate;
   try(var input=Files.newInputStream(directory.resolve("chain.pem"))){certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);}
   String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
   String state="VALID";
   try{certificate.checkValidity(Date.from(clock.instant()));}catch(CertificateExpiredException e){state="EXPIRED";}catch(CertificateNotYetValidException e){state="NOT_YET_VALID";}
   if(!Files.isRegularFile(directory.resolve("key.pem")))state="KEY_UNAVAILABLE";
   return new Status(true,state.equals("VALID"),state,Files.exists(directory.resolve("private-provider"))?"private-certificate":"development",false,fingerprint,certificate.getNotAfter().toInstant());
  } catch(Exception e){return new Status(true,false,"INVALID","development",false,"unavailable",null);}
 }
 public boolean available(){return status().available();}
 public synchronized Material material() throws Exception {
  var scope=scoped();if(scope!=this)return scope.material();
  Status status=status();
  if(!status.available())throw new ResponseStatusException(HttpStatus.CONFLICT,"Signing identity is unavailable or expired; replace it in the UI");
  Path directory=directory();return new Material(directory.resolve("chain.pem"),directory.resolve("key.pem"),status.fingerprint(),status.provider().equals("development"));
 }
 public record Creation(Status status,boolean created) {}
 public synchronized Creation create() throws Exception {
  var scope=scoped();if(scope!=this)return scope.create();
  if(status().configured())return new Creation(status(),false);
  publish(generate());return new Creation(status(),true);
 }
 public synchronized Status rotate(String expectedFingerprint) throws Exception {
  var scope=scoped();if(scope!=this)return scope.rotate(expectedFingerprint);
  Status current=status();
  if(!current.configured())throw new ResponseStatusException(HttpStatus.CONFLICT,"Create an identity first");
  if(!Objects.equals(expectedFingerprint,current.fingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Identity changed; reload before rotation");
  publish(generate());return status();
 }

 public synchronized Status importPrivate(byte[] bundle,char[] password,String expectedFingerprint) throws Exception {
  var scope=scoped();if(scope!=this)return scope.importPrivate(bundle,password,expectedFingerprint);
  Path temporary=null;
  try {
   if(bundle.length==0 || bundle.length>1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PKCS#12 bundle must be under 1 MiB");
   if(!Objects.equals(expectedFingerprint,status().fingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Identity changed; reload before import");
   var store=java.security.KeyStore.getInstance("PKCS12");store.load(new java.io.ByteArrayInputStream(bundle),password);
   var aliases=Collections.list(store.aliases()).stream().filter(a->{try{return store.isKeyEntry(a);}catch(Exception e){return false;}}).toList();
   if(aliases.size()!=1)throw new IllegalArgumentException();
   var key=store.getKey(aliases.getFirst(),password);var chain=store.getCertificateChain(aliases.getFirst());
   if(!(key instanceof java.security.interfaces.ECPrivateKey ec) || ec.getParams().getOrder().bitLength()!=256 || chain==null || chain.length<2)throw new IllegalArgumentException();
   StringBuilder pem=new StringBuilder();
   for(int i=0;i<chain.length;i++){
    var cert=(X509Certificate)chain[i];cert.checkValidity(Date.from(clock.instant()));
    if(i+1<chain.length){var issuer=(X509Certificate)chain[i+1];if(!cert.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()) || issuer.getBasicConstraints()<0 || (issuer.getKeyUsage()!=null && !issuer.getKeyUsage()[5]))throw new IllegalArgumentException();cert.verify(issuer.getPublicKey());}
    pem.append(pem("CERTIFICATE",cert.getEncoded()));
   }
   var leaf=(X509Certificate)chain[0];var eku=leaf.getExtendedKeyUsage();
   if(leaf.getBasicConstraints()!=-1 || eku==null || !(eku.contains("1.3.6.1.5.5.7.3.4") || eku.contains("1.3.6.1.4.1.62558.2.1")) || (leaf.getKeyUsage()!=null && !leaf.getKeyUsage()[0]))throw new IllegalArgumentException();
   var signature=java.security.Signature.getInstance("SHA256withECDSA");byte[] challenge=new byte[32];new java.security.SecureRandom().nextBytes(challenge);signature.initSign((java.security.PrivateKey)key);signature.update(challenge);byte[] proof=signature.sign();signature.initVerify(leaf.getPublicKey());signature.update(challenge);if(!signature.verify(proof))throw new IllegalArgumentException();
   Files.createDirectories(storage);Files.setPosixFilePermissions(storage,PosixFilePermissions.fromString("rwx------"));
   temporary=Files.createTempDirectory(storage,"private-");Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rwx------"));
   Files.writeString(temporary.resolve("chain.pem"),pem.toString());
   Files.writeString(temporary.resolve("key.pem"),pem("PRIVATE KEY",key.getEncoded()),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(temporary.resolve("key.pem"),PosixFilePermissions.fromString("rw-------"));
   Files.writeString(temporary.resolve("private-provider"),"PKCS12 import");
   // Run the actual C2PA SDK before publishing the replacement identity.
   Files.write(temporary.resolve("probe.png"),Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
   Files.writeString(temporary.resolve("probe.json"),"{\"title\":\"Identity readiness probe\",\"format\":\"image/png\",\"claim_generator_info\":[{\"name\":\"C2PA Trust Portal\"}]}");
   Path worker=Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize();
   execute(temporary,worker.toString(),"sign","probe.png","signed-probe.png","probe.json","chain.pem","key.pem");
   Files.delete(temporary.resolve("probe.png"));Files.delete(temporary.resolve("probe.json"));Files.delete(temporary.resolve("signed-probe.png"));
   Path version=storage.resolve(UUID.randomUUID().toString());Files.move(temporary,version,StandardCopyOption.ATOMIC_MOVE);temporary=null;publish(version);return status();
  } catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Import failed. Supply one EC P-256 signing key with a valid C2PA-compatible certificate chain and matching password; the Rust worker must be available.");}
  finally{Arrays.fill(password,'\0');Arrays.fill(bundle,(byte)0);if(temporary!=null && Files.exists(temporary)){try(var files=Files.list(temporary)){for(Path f:files.toList())Files.deleteIfExists(f);}Files.deleteIfExists(temporary);}}
 }
 private String pem(String label,byte[] bytes){return "-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+label+"-----\n";}
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
