package com.c2pa.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name="adminToken")
public class SigningController {
 private final DevelopmentIdentity identity;
 private final ConfigurationRepository repository;
 private final ObjectMapper mapper; private final AuditService audit;
 public SigningController(DevelopmentIdentity identity,ConfigurationRepository repository,ObjectMapper mapper,AuditService audit){this.identity=identity;this.repository=repository;this.mapper=mapper;this.audit=audit;}
 @GetMapping("/admin/signing-identity") public DevelopmentIdentity.Status identity(){return identity.status();}
 @PostMapping("/admin/signing-identity/development") public DevelopmentIdentity.Status create(@RequestBody Map<String,Boolean> request) throws Exception {
  if(!Boolean.TRUE.equals(request.get("acknowledgeUntrusted")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge development-only identity");
  var result=identity.create();if(result.created())audit.record("DEVELOPMENT_IDENTITY_CREATED",result.status().fingerprint());return result.status();
 }
 public record Rotation(@jakarta.validation.constraints.NotBlank String expectedFingerprint,boolean acknowledgeUntrusted) {}
 @PostMapping("/admin/signing-identity/development/rotate")
 public DevelopmentIdentity.Status rotate(@jakarta.validation.Valid @RequestBody Rotation request) throws Exception {
  if(!request.acknowledgeUntrusted())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge development-only rotation");
  var result=identity.rotate(request.expectedFingerprint());audit.record("DEVELOPMENT_IDENTITY_ROTATED",result.fingerprint());return result;
 }
 @PostMapping(value="/admin/signing-identity/private",consumes="multipart/form-data")
 public DevelopmentIdentity.Status importPrivate(@RequestPart("bundle") MultipartFile bundle,@RequestParam String password,@RequestParam(required=false) String expectedFingerprint,@RequestParam boolean acknowledgeLocalKeyStorage)throws Exception {
  if(!acknowledgeLocalKeyStorage)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Acknowledge local private-key storage");
  if(bundle.getSize()>1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bundle exceeds 1 MiB");
  var result=identity.importPrivate(bundle.getBytes(),password.toCharArray(),expectedFingerprint);audit.record("PRIVATE_IDENTITY_IMPORTED",result.fingerprint());return result;
 }
 @PostMapping(value="/signing",consumes="multipart/form-data")
 public ResponseEntity<byte[]> sign(@RequestPart("file") MultipartFile file,@RequestParam String creator,@RequestParam String title,@RequestParam(required=false,defaultValue="unspecified") String aiDisclosure,@RequestParam boolean acknowledgePublicClaims) throws Exception {
  if(!acknowledgePublicClaims)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review public claims before signing");
  if(creator.isBlank() || creator.length()>120 || title.isBlank() || title.length()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Creator and title are required and must fit field limits");
  if(!Set.of("none","generated","edited","unspecified").contains(aiDisclosure))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid AI disclosure");
  if(!identity.available())throw new ResponseStatusException(HttpStatus.CONFLICT,"Configure a signing identity first");
  var record=repository.findById(1L).filter(r->r.active!=null).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Activate a profile first"));
  var settings=mapper.readValue(record.active,ConfigurationController.Settings.class);
  if(settings.requireAiDisclosure() && aiDisclosure.equals("unspecified"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AI disclosure is required by the active profile");
  if(file.isEmpty() || file.getSize()>settings.maxUploadMb()*1024L*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File is empty or exceeds profile limits");
  byte[] header;try(var stream=file.getInputStream()){header=stream.readNBytes(8);}
  String format=header.length>=3 && (header[0]&255)==255 && (header[1]&255)==216 && (header[2]&255)==255?"image/jpeg":Arrays.equals(header,new byte[]{(byte)137,80,78,71,13,10,26,10})?"image/png":null;
  if(format==null || !settings.formats().contains(format))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Unsupported or disabled content format");
  Path worker=Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize();
  if(!Files.isExecutable(worker))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Build the Rust worker first");
  DevelopmentIdentity.Material material=identity.material();
  Path directory=Files.createTempDirectory("c2pa-signing-");Process process=null;
  try {
   String extension=format.equals("image/png")?".png":".jpg";
   Path input=directory.resolve("original"+extension),output=directory.resolve("signed"+extension),manifest=directory.resolve("manifest.json");file.transferTo(input);
   var declarations=Map.of("creator",creator,"organization",settings.organizationName(),"profile",settings.profileName(),"configurationRevision",record.activeRevision==null?record.revision:record.activeRevision,"aiDisclosure",aiDisclosure,"source","user-declared","developmentIdentity",material.development());
   var definition=Map.of("claim_generator_info",List.of(Map.of("name","C2PA Trust Portal","version","0.2.0")),"title",title,"format",format,"assertions",List.of(Map.of("label","com.c2pa.portal.declarations","data",declarations)));
   Files.writeString(manifest,mapper.writeValueAsString(definition));
   process=new ProcessBuilder(worker.toString(),"sign",input.toString(),output.toString(),manifest.toString(),material.certificate().toString(),material.key().toString()).redirectOutput(directory.resolve("report.json").toFile()).redirectError(directory.resolve("error.log").toFile()).start();
   if(!process.waitFor(45,TimeUnit.SECONDS))throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"Signing timed out");
   if(process.exitValue()!=0 || !Files.exists(output))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Signing failed: unsupported content, invalid provenance, or invalid signing certificate");
   audit.record("CONTENT_SIGNED",material.fingerprint()+"/configuration-"+(record.activeRevision==null?record.revision:record.activeRevision));
   return ResponseEntity.ok().contentType(MediaType.parseMediaType(format)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"signed-content"+extension+"\"").header("X-Signing-Identity",material.development()?"development-untrusted":"private-certificate-trust-unverified").body(Files.readAllBytes(output));
  } finally {
   if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
   try(var paths=Files.list(directory)){for(Path path:paths.toList())Files.deleteIfExists(path);}Files.deleteIfExists(directory);
  }
 }
}
