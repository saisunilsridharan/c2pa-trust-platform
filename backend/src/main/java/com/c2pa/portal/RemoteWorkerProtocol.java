package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.io.*;
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="workerPairing")
@RestController @RequestMapping("/api/v1/worker-protocol")
public class RemoteWorkerProtocol {
 public record Claim(String id,String lease,String extension,String manifest,String certificate,String policy,boolean timestamp,int timeout,int memoryMb,int cpuSeconds,String sandboxMode,String sourceSha256){}
 private final RemoteWorkerLeases leases;private final JobService jobs;private final WorkerExecution execution;private final HardwareSigning hardware;private final PrivateTimestamps timestamps;private final CertificateRevocations revocations;private final TrustPolicy trust;private final ObjectMapper mapper;private final PrivateObjectStorage objects;
 public RemoteWorkerProtocol(RemoteWorkerLeases leases,JobService jobs,WorkerExecution execution,HardwareSigning hardware,PrivateTimestamps timestamps,CertificateRevocations revocations,TrustPolicy trust,ObjectMapper mapper,PrivateObjectStorage objects){this.leases=leases;this.jobs=jobs;this.execution=execution;this.hardware=hardware;this.timestamps=timestamps;this.revocations=revocations;this.trust=trust;this.mapper=mapper;this.objects=objects;}
 private String worker(HttpServletRequest r){return (String)r.getAttribute("portal.workerId");}
 private String manifest(SigningJob j)throws Exception{var definition=mapper.readTree(jobs.directory(j.id).resolve("manifest.json").toFile());var declarations=(com.fasterxml.jackson.databind.node.ObjectNode)definition.path("assertions").get(0).path("data");declarations.put("remoteAttemptHash",RemoteWorkerSecurity.hash(j.leaseToken));declarations.put("remoteWorkerId",j.remoteWorkerId);return mapper.writeValueAsString(definition);}
 static byte[] bounded(InputStream in,int limit)throws IOException{byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);return bytes;}
 private SigningJob job(String id,HttpServletRequest r){try{UUID.fromString(id);}catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}return leases.access(worker(r),id,r.getHeader("X-Worker-Lease"));}
 @PostMapping("/heartbeat") public Map<String,Object> heartbeat(HttpServletRequest r)throws Exception{
  var info=mapper.readTree(bounded(r.getInputStream(),4096));if(info.path("protocol").asInt()!=1 || !info.path("resourceLimitsAvailable").asBoolean())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported worker capabilities");return leases.heartbeat(worker(r),info.path("namespaceIsolationAvailable").asBoolean());
 }
 @PostMapping("/claim") public ResponseEntity<Claim> claim(HttpServletRequest r)throws Exception{
  var claimed=leases.claim(worker(r));if(claimed.isEmpty())return ResponseEntity.noContent().build();var j=claimed.get();
  byte[] original=jobs.readAsset(j,"original"+jobs.extension(j),100*1024*1024L);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
  String definition=manifest(j);if(!hash.equals(mapper.readTree(definition).path("assertions").get(0).path("data").path("sourceSha256").asText()))throw new IllegalStateException("Stored original hash changed");
  return ResponseEntity.ok(new Claim(j.id,j.leaseToken,jobs.extension(j),definition,Files.readString(Path.of(j.certificatePath)),trust.workerConfiguration(j.trustSnapshot,j.timestampSnapshot),j.timestampSnapshot!=null,j.workerTimeoutSeconds,j.maxMemoryMb,j.maxCpuSeconds,j.sandboxMode,hash));
 }
 @GetMapping("/jobs/{id}/source") public ResponseEntity<byte[]> source(@PathVariable String id,HttpServletRequest r)throws Exception{var j=job(id,r);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(jobs.readAsset(j,"original"+jobs.extension(j),100*1024*1024L));}
 @PostMapping("/jobs/{id}/callback") public ResponseEntity<byte[]> callback(@PathVariable String id,@RequestHeader("X-Worker-Operation")int operation,HttpServletRequest r)throws Exception{
  if(operation!=1 && operation!=2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var j=leases.callback(worker(r),id,r.getHeader("X-Worker-Lease"));byte[] input=bounded(r.getInputStream(),65536);if(input.length==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  revocations.enforce(j.workspaceId,Path.of(j.certificatePath));byte[] result;
  if(operation==1){if(!j.keyPath.startsWith("pkcs11:"))throw new ResponseStatusException(HttpStatus.CONFLICT);result=hardware.sign(j.keyPath.substring(7),j.workspaceId,input);}else{if(j.timestampSnapshot==null)throw new ResponseStatusException(HttpStatus.CONFLICT);result=timestamps.submit(j.workspaceId,j.timestampSnapshot,input);}
  leases.access(worker(r),id,j.leaseToken);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(result);
 }
 static void verifyReport(JsonNode report,JsonNode expected,String fingerprint)throws Exception{
  String state=report.path("validation_state").asText();if(!Set.of("Valid","Trusted").contains(state))throw new IllegalStateException("SDK rejected remote output");
  var active=report.path("manifests").path(report.path("active_manifest").asText());
  if(active.has("title") && !active.path("title").equals(expected.path("title")))throw new IllegalStateException("Remote title changed");
  if(active.has("format") && !active.path("format").equals(expected.path("format")))throw new IllegalStateException("Remote format changed");
  var wanted=expected.path("assertions").get(0);int matches=0;
  if(!wanted.path("data").path("title").equals(expected.path("title")) || !wanted.path("data").path("contentFormat").equals(expected.path("format")))throw new IllegalStateException("Captured signed title or format missing");
  for(var assertion:active.path("assertions")){if(assertion.path("label").asText().equals("com.c2pa.portal.declarations")){matches++;if(!assertion.path("data").equals(wanted.path("data")))throw new IllegalStateException("Remote declarations changed");}}
  if(matches!=1)throw new IllegalStateException("Remote declarations missing");
  String pem=report.path("portal_signing_certificate_chain").asText();if(pem.length()>60000)throw new IllegalStateException();
  var cert=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())).equals(fingerprint))throw new IllegalStateException("Remote signer changed");
 }
 @PostMapping("/jobs/{id}/complete") public Map<String,Object> complete(@PathVariable String id,HttpServletRequest r)throws Exception{
  var j=job(id,r);Path scratch=Files.createTempDirectory("c2pa-remote-inspection-");
  try{
   Path signed=scratch.resolve("signed"+jobs.extension(j));try(var output=Files.newOutputStream(signed)){byte[] buffer=new byte[8192];long total=0;int n;while((n=r.getInputStream().read(buffer))!=-1){total+=n;if(total>128*1024*1024L)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);output.write(buffer,0,n);}if(total==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
   if(!j.format.equals(ContentFormats.detect(signed)))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Remote content format changed");
   Path report=scratch.resolve("report.json");String policy=trust.workerConfiguration(j.trustSnapshot,j.timestampSnapshot);
   int exit=execution.inspect(j.workspaceId,Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize(),signed,policy,report,scratch.resolve("error.log"),j.workerTimeoutSeconds,new WorkerSandbox.Budget(j.maxMemoryMb,j.maxCpuSeconds,j.sandboxMode));
   if(exit!=0 || Files.size(report)>8*1024*1024L)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Central SDK inspection rejected output");
   var result=mapper.readTree(report.toFile());try{verifyReport(result,mapper.readTree(manifest(j)),j.fingerprint);}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Remote output does not match captured claims, attempt or signer");}
   if(policy!=null){var p=mapper.readTree(policy);if(p.path("requireTrusted").asBoolean() && !result.path("validation_state").asText().equals("Trusted") || p.path("requireTimestamp").asBoolean() && !result.path("portal_trust_policy").path("privateTimestampTrusted").asBoolean())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Captured trust policy rejected output");}
   j.state="COMPLETED";j.error=null;
   leases.finish(worker(r),j,()->{revocations.enforce(j.workspaceId,Path.of(j.certificatePath));Path attempt=jobs.directory(j.id).resolve("attempts").resolve(j.leaseToken);Files.createDirectories(attempt);Path target=attempt.resolve("signed"+jobs.extension(j));Files.move(signed,target,StandardCopyOption.REPLACE_EXISTING);Files.copy(report,attempt.resolve("report.json"),StandardCopyOption.REPLACE_EXISTING);objects.write(j,"signed"+jobs.extension(j),target);objects.write(j,"report.json",attempt.resolve("report.json"));});
   return Map.of("state","COMPLETED","id",j.id);
  }finally{try(var files=Files.walk(scratch)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
 @PostMapping("/jobs/{id}/fail") public Map<String,Object> fail(@PathVariable String id,HttpServletRequest r)throws Exception{var j=job(id,r);j.state="FAILED";j.error="Remote signing failed. Review worker readiness and provider policy.";leases.finish(worker(r),j,()->{});return Map.of("state","FAILED");}
}
