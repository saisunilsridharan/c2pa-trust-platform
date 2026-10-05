package com.c2pa.portal;

import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

@Service
public class DevelopmentIdentity {
 private final Path directory=Path.of(".local/development-identity").toAbsolutePath();
 public boolean available(){return Files.isRegularFile(directory.resolve("chain.pem")) && Files.isRegularFile(directory.resolve("key.pem"));}
 public Path certificate(){return directory.resolve("chain.pem");}
 public Path key(){return directory.resolve("key.pem");}
 public synchronized void create() throws Exception {
  if(available())return;
  Files.createDirectories(directory.getParent());
  Path temporary=Files.createTempDirectory(directory.getParent(),"identity-");
  try {
   execute(temporary,"openssl","req","-x509","-newkey","ec","-pkeyopt","ec_paramgen_curve:P-256","-nodes","-keyout","root-key.pem","-out","root.pem","-days","30","-subj","/CN=Portal Development Root","-addext","basicConstraints=critical,CA:TRUE","-addext","keyUsage=critical,keyCertSign,cRLSign");
   execute(temporary,"openssl","req","-new","-newkey","ec","-pkeyopt","ec_paramgen_curve:P-256","-nodes","-keyout","key.pem","-out","request.pem","-subj","/CN=Portal Development Signer");
   Files.writeString(temporary.resolve("extensions.cnf"),"basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=emailProtection,1.3.6.1.4.1.62558.2.1\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n");
   execute(temporary,"openssl","x509","-req","-in","request.pem","-CA","root.pem","-CAkey","root-key.pem","-CAcreateserial","-out","leaf.pem","-days","30","-extfile","extensions.cnf");
   Files.writeString(temporary.resolve("chain.pem"),Files.readString(temporary.resolve("leaf.pem"))+Files.readString(temporary.resolve("root.pem")));
   Files.setPosixFilePermissions(temporary.resolve("key.pem"),PosixFilePermissions.fromString("rw-------"));
   Files.delete(temporary.resolve("root-key.pem"));
   if(Files.exists(directory))throw new IllegalStateException("Existing incomplete signing identity requires recovery");
   Files.move(temporary,directory,StandardCopyOption.ATOMIC_MOVE);
  } finally {
   if(Files.exists(temporary)) {
    try(var paths=Files.list(temporary)){for(Path file:paths.toList())Files.deleteIfExists(file);}
    Files.deleteIfExists(temporary);
   }
  }
 }
 private void execute(Path directory,String... command) throws Exception {
  Process process=new ProcessBuilder(command).directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  try {
   if(!process.waitFor(15,TimeUnit.SECONDS) || process.exitValue()!=0)throw new IllegalStateException("Development certificate generation failed");
  } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
 }
}
