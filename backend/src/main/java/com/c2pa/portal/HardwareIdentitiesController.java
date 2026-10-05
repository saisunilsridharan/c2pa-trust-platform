package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.*;
import java.security.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/hardware-identities")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class HardwareIdentitiesController {
 public record Draft(HardwareSigning.Configuration configuration,String certificateChainPem){}
 public record View(String id,HardwareSigning.Configuration configuration,String fingerprint,Instant createdAt,Instant testedAt,Instant expiresAt){}
 public record Approval(String label,Long expectedProfileRevision,String expectedIdentityFingerprint,boolean acknowledgePrivateTrust){}
 private final org.springframework.transaction.support.TransactionTemplate transactions;private final HardwareCertificateRequests certificateRequests;private final HardwareIdentityRepository identities;private final HardwareSigning hardware;private final WorkerExecution execution;private final SigningChoices choices;private final SigningOptionRepository options;private final com.fasterxml.jackson.databind.ObjectMapper mapper;private final AuditService audit;private final WorkspaceRepository workspaces;
 public HardwareIdentitiesController(HardwareIdentityRepository identities,HardwareSigning hardware,WorkerExecution execution,SigningChoices choices,SigningOptionRepository options,com.fasterxml.jackson.databind.ObjectMapper mapper,AuditService audit,WorkspaceRepository workspaces,HardwareCertificateRequests certificateRequests,org.springframework.transaction.PlatformTransactionManager transactionManager){this.transactions=new org.springframework.transaction.support.TransactionTemplate(transactionManager);this.certificateRequests=certificateRequests;this.identities=identities;this.hardware=hardware;this.execution=execution;this.choices=choices;this.options=options;this.mapper=mapper;this.audit=audit;this.workspaces=workspaces;}
 private View view(HardwareIdentity i)throws Exception{return new View(i.id,hardware.decode(i),i.fingerprint,i.createdAt,i.testedAt,certificateRequests.certificate(i).getNotAfter().toInstant());}
 @GetMapping public List<View> list(@RequestParam(defaultValue="0") int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);List<View> result=new ArrayList<>();for(var i:identities.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))result.add(view(i));return result;}
 @PostMapping @Transactional public View draft(@RequestBody Draft request)throws Exception{
  var configuration=hardware.validate(WorkspaceContext.id(),request.configuration());var certificates=hardware.certificates(request.certificateChainPem());
  var i=new HardwareIdentity();i.id=UUID.randomUUID().toString();i.workspaceId=WorkspaceContext.id();i.configuration=mapper.writeValueAsString(configuration);i.fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificates.getFirst().getEncoded()));i.createdAt=Instant.now();
  Path directory=Path.of(".local/hardware-identities",i.workspaceId.toString(),i.id).toAbsolutePath();Files.createDirectories(directory);Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));Path certificate=directory.resolve("chain.pem");Files.writeString(certificate,request.certificateChainPem());i.certificatePath=certificate.toString();
  try{identities.saveAndFlush(i);audit.record("HARDWARE_IDENTITY_DRAFT_CREATED",i.id);return view(i);}catch(Exception e){Files.deleteIfExists(certificate);Files.deleteIfExists(directory);throw e;}
 }
 @PostMapping("/{id}/test") @Transactional public View test(@PathVariable String id)throws Exception{
  var identity=hardware.get(id,WorkspaceContext.id());Path directory=Files.createTempDirectory("c2pa-hsm-probe-");
  try{
   Files.write(directory.resolve("probe.png"),Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));Files.writeString(directory.resolve("manifest.json"),"{\"title\":\"Hardware identity readiness probe\",\"format\":\"image/png\",\"claim_generator_info\":[{\"name\":\"C2PA Trust Portal\"}]}");
   Path worker=Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize();int code=execution.sign(identity.workspaceId,worker,directory.resolve("probe.png"),directory.resolve("signed.png"),directory.resolve("manifest.json"),Path.of(identity.certificatePath),"pkcs11:"+identity.id,directory.resolve("report.json"),directory.resolve("error.log"),45);
   if(code!=0 || !Files.isRegularFile(directory.resolve("signed.png")))throw new IllegalStateException();identity.testedAt=Instant.now();identities.saveAndFlush(identity);audit.record("HARDWARE_IDENTITY_SIGNING_TESTED",identity.id);return view(identity);
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Hardware signing probe failed; check module, slot, PIN, alias, certificate and worker readiness");}
  finally{try(var files=Files.list(directory)){for(var file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(directory);}
 }
 public record Csr(String expectedIdentityFingerprint,HardwareCertificateRequests.Subject subject,boolean acknowledgeCertificateSubject){}
 public record Renewal(String expectedIdentityFingerprint,String certificateChainPem,boolean acknowledgeCertificateReplacement){}
 @PostMapping("/{id}/csr") @Transactional public org.springframework.http.ResponseEntity<byte[]> csr(@PathVariable String id,@RequestBody Csr request)throws Exception{
  var identity=hardware.get(id,WorkspaceContext.id());if(!request.acknowledgeCertificateSubject())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review the certificate subject first");if(!Objects.equals(identity.fingerprint,request.expectedIdentityFingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Review the selected certificate again");
  byte[] csr=certificateRequests.csr(identity,request.subject());audit.record("HARDWARE_CERTIFICATE_REQUEST_CREATED",identity.id);
  return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("application/x-pem-file")).header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"signing-certificate-request.pem\"").body(csr);
 }
 @PostMapping("/{id}/renewal") public View renew(@PathVariable String id,@RequestBody Renewal request)throws Exception{
  var old=hardware.get(id,WorkspaceContext.id());if(!request.acknowledgeCertificateReplacement())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review the replacement certificate first");if(!Objects.equals(old.fingerprint,request.expectedIdentityFingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Review the selected certificate again");
  var chain=hardware.certificates(request.certificateChainPem());var previous=certificateRequests.certificate(old);
  if(!java.security.MessageDigest.isEqual(previous.getPublicKey().getEncoded(),chain.getFirst().getPublicKey().getEncoded()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Replacement certificate must use the existing token key");
  if(java.security.MessageDigest.isEqual(previous.getEncoded(),chain.getFirst().getEncoded()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide a newly issued certificate");
  var configuration=hardware.decode(old);
  // Commit the immutable draft before the separate callback thread reads it.
  var created=transactions.execute(status->{try{return draft(new Draft(configuration,request.certificateChainPem()));}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}});
  // Failed probes preserve the committed draft for a later test; existing choices remain unchanged.
  return transactions.execute(status->{try{var result=test(created.id());audit.record("HARDWARE_CERTIFICATE_RENEWAL_TESTED",created.id());return result;}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}});
 }
 @PostMapping("/{id}/approve") @Transactional public SigningOptionsController.View approve(@PathVariable String id,@RequestBody Approval request)throws Exception{
  if(request.label()==null || request.label().isBlank() || request.label().length()>120 || !request.acknowledgePrivateTrust())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  workspaces.lockById(WorkspaceContext.id()).orElseThrow();var i=hardware.get(id,WorkspaceContext.id());if(i.testedAt==null || i.testedAt.isBefore(Instant.now().minusSeconds(86400)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Test this hardware version before approval");
  var selected=choices.profile();if(!Objects.equals(selected.profileRevision(),request.expectedProfileRevision()) || !Objects.equals(i.fingerprint,request.expectedIdentityFingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Profile or hardware certificate changed; review again");
  var o=new SigningOption();o.id=UUID.randomUUID().toString();o.workspaceId=i.workspaceId;o.label=request.label().trim();o.settings=mapper.writeValueAsString(selected.settings());o.profileRevision=selected.profileRevision();o.fingerprint=i.fingerprint;o.certificatePath=i.certificatePath;o.keyPath="pkcs11:"+i.id;o.development=false;o.createdAt=Instant.now();choices.material(o);options.saveAndFlush(o);audit.record("HARDWARE_SIGNING_CHOICE_APPROVED",o.id);
  return new SigningOptionsController.View(o.id,o.revision,o.label,selected.settings(),o.profileRevision,o.fingerprint,false,true,true,o.createdAt,"PKCS11");
 }
}
