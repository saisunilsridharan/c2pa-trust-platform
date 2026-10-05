package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
@Service public class HardwareSigning {
 public record Configuration(String module,int slotListIndex,String keyAlias,String pinCredential){}
 private final HardwareIdentityRepository identities;private final CredentialService credentials;private final ObjectMapper mapper;
 public HardwareSigning(HardwareIdentityRepository identities,CredentialService credentials,ObjectMapper mapper){this.identities=identities;this.credentials=credentials;this.mapper=mapper;}
 public Configuration decode(HardwareIdentity identity)throws Exception{return mapper.readValue(identity.configuration,Configuration.class);}
 public Configuration validate(Long workspace,Configuration c)throws Exception{
  try{
   if(c==null || c.module()==null || c.module().length()>2000 || c.keyAlias()==null || c.keyAlias().isBlank() || c.keyAlias().length()>200 || c.slotListIndex()<0 || c.slotListIndex()>128 || c.pinCredential()==null || !c.pinCredential().matches("[a-f0-9-]{36}"))throw new IllegalArgumentException();
   Path module=Path.of(c.module()).toRealPath();if(!Files.isRegularFile(module) || !module.getFileName().toString().endsWith(".so") || c.module().contains("\n") || c.module().contains("\r") || c.module().contains("\""))throw new IllegalArgumentException();
   boolean system=(module.startsWith("/usr/lib") || module.startsWith("/usr/local/lib") || module.startsWith("/opt")) && Files.getOwner(module).getName().equals("root");
   if(system){
    for(Path ancestor=module;ancestor!=null;ancestor=ancestor.getParent()){
     var permissions=Files.getPosixFilePermissions(ancestor);
     if(!Files.getOwner(ancestor).getName().equals("root") || permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)){system=false;break;}
    }
   }
   boolean development=module.equals(Path.of("/workspace/tools/softhsm/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so"));
   if(!system && !development)throw new IllegalArgumentException();byte[] pin=credentials.read(workspace,c.pinCredential());try{if(pin.length<1 || pin.length>256)throw new IllegalArgumentException();}finally{Arrays.fill(pin,(byte)0);}
   return new Configuration(module.toString(),c.slotListIndex(),c.keyAlias(),c.pinCredential());
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an installed, trusted PKCS#11 module, slot index, key alias and workspace PIN credential");}
 }
 public List<X509Certificate> certificates(String pem)throws Exception{
  try{
   if(pem==null || pem.length()>64000)throw new IllegalArgumentException();
   var chain=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII))).stream().map(c->(X509Certificate)c).toList();
   if(chain.size()<2 || chain.size()>10)throw new IllegalArgumentException();
   for(int i=0;i<chain.size();i++){var c=chain.get(i);c.checkValidity();if(i+1<chain.size()){var parent=chain.get(i+1);if(!c.getIssuerX500Principal().equals(parent.getSubjectX500Principal()) || parent.getBasicConstraints()<0 || (parent.getKeyUsage()!=null && !parent.getKeyUsage()[5]))throw new IllegalArgumentException();c.verify(parent.getPublicKey());}}
   var leaf=chain.getFirst();var eku=leaf.getExtendedKeyUsage();if(!(leaf.getPublicKey() instanceof java.security.interfaces.ECPublicKey ec) || ec.getParams().getOrder().bitLength()!=256 || leaf.getBasicConstraints()!=-1 || (leaf.getKeyUsage()!=null && !leaf.getKeyUsage()[0]) || eku==null || !(eku.contains("1.3.6.1.4.1.62558.2.1") || eku.contains("1.3.6.1.5.5.7.3.4")))throw new IllegalArgumentException();return chain;
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide a valid EC P-256 C2PA signing certificate and issuer chain");}
 }
 public HardwareIdentity get(String id,Long workspace){return identities.findById(id).filter(i->i.workspaceId.equals(workspace)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 public byte[] sign(String id,Long workspace,byte[] data)throws Exception{
  var identity=get(id,workspace);var c=validate(workspace,decode(identity));var chain=certificates(Files.readString(Path.of(identity.certificatePath)));if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chain.getFirst().getEncoded())).equals(identity.fingerprint))throw new IllegalStateException();
  byte[] pin=credentials.read(workspace,c.pinCredential());Process process=null;Path scratch=Files.createTempDirectory("c2pa-pkcs11-process-");
  try{
   Files.setPosixFilePermissions(scratch,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
   String classpath=System.getProperty("java.class.path"),javaBinary=Path.of(System.getProperty("java.home"),"bin","java").toString();
   List<String> command=new ArrayList<>(List.of(javaBinary,"-Xmx128m","-XX:MaxMetaspaceSize=128m","-XX:MaxDirectMemorySize=32m","-Djava.io.tmpdir="+scratch));
   boolean packaged=classpath.endsWith(".jar") && !classpath.contains(System.getProperty("path.separator"));
   if(packaged)command.add("-Dloader.main=com.c2pa.portal.Pkcs11ToolMain");command.addAll(List.of("-cp",classpath,packaged?"org.springframework.boot.loader.launch.PropertiesLauncher":"com.c2pa.portal.Pkcs11ToolMain"));
   var child=new ProcessBuilder(command).redirectOutput(scratch.resolve("result.json").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);child.environment().clear();child.environment().put("PATH","/usr/bin:/bin");for(String name:List.of("SOFTHSM2_CONF","LD_LIBRARY_PATH")){String value=System.getenv(name);if(value!=null)child.environment().put(name,value);}process=child.start();
   try(var input=process.getOutputStream()){mapper.writeValue(input,Map.of("module",c.module(),"slotListIndex",c.slotListIndex(),"alias",c.keyAlias(),"pin",new String(pin,java.nio.charset.StandardCharsets.UTF_8),"data",Base64.getEncoder().encodeToString(data)));}
   if(!process.waitFor(15,TimeUnit.SECONDS) || process.exitValue()!=0 || Files.size(scratch.resolve("result.json"))>4096)throw new IllegalStateException();
   var result=mapper.readTree(Files.readAllBytes(scratch.resolve("result.json")));byte[] signature=Base64.getDecoder().decode(result.path("signature").asText());var verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(chain.getFirst());verifier.update(data);if(!verifier.verify(signature))throw new IllegalStateException();return signature;
  }finally{Arrays.fill(pin,(byte)0);if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}try(var files=Files.walk(scratch)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
 }
}
