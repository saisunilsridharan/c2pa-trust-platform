package com.c2pa.portal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import java.nio.file.*;
import java.time.Instant;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
@Service
public class JobService {
 private final JobRepository jobs;private final ConfigurationRepository configs;private final DevelopmentIdentity identity;private final ObjectMapper mapper;private final AuditRepository audit;private final StorageRepository retention;
 private final Path storage=Path.of(".local/assets").toAbsolutePath();
 public JobService(JobRepository jobs,ConfigurationRepository configs,DevelopmentIdentity identity,ObjectMapper mapper,AuditRepository audit,StorageRepository retention){this.jobs=jobs;this.configs=configs;this.identity=identity;this.mapper=mapper;this.audit=audit;this.retention=retention;}
 public Path directory(String id){UUID.fromString(id);return storage.resolve(id);}
 public synchronized SigningJob submit(MultipartFile file,String creator,String title,String ai,String owner,String requestId,Long expectedProfileRevision,String expectedIdentityFingerprint) throws Exception {
  if(creator.isBlank() || creator.length()>120 || title.isBlank() || title.length()>200 || !Set.of("none","generated","edited","unspecified").contains(ai))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid public claims");
  if(!requestId.matches("[a-zA-Z0-9-]{1,80}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request identifier");
  String requestKey=owner+":"+requestId;
  MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=file.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
  String contentHash=HexFormat.of().formatHex(digest.digest());
  digest.update((contentHash+mapper.writeValueAsString(List.of(creator,title,ai))).getBytes(java.nio.charset.StandardCharsets.UTF_8));String requestDigest=HexFormat.of().formatHex(digest.digest());
  var existing=jobs.findByRequestKey(requestKey);if(existing.isPresent()){if(!Objects.equals(existing.get().requestDigest,requestDigest))throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key already used for different content or claims");return existing.get();}
  var record=configs.findById(1L).filter(c->c.active!=null).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Activate a profile first"));
  var settings=mapper.readValue(record.active,ConfigurationController.Settings.class);
  if(settings.requireAiDisclosure() && ai.equals("unspecified"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AI declaration required");
  if(file.isEmpty() || file.getSize()>settings.maxUploadMb()*1024L*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File exceeds profile limits");
  String format=ContentFormats.detect(file);
  if(format==null || !settings.formats().contains(format))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Unsupported or disabled format");
  var material=identity.material();if(!Objects.equals(expectedProfileRevision,record.activeRevision==null?record.revision:record.activeRevision) || !Objects.equals(expectedIdentityFingerprint,material.fingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Profile or identity changed; reload and review public claims again");SigningJob job=new SigningJob();job.id=UUID.randomUUID().toString();job.owner=owner;job.requestKey=requestKey;job.requestDigest=requestDigest;job.state="QUEUED";job.format=format;job.title=title;job.fingerprint=material.fingerprint();job.certificatePath=material.certificate().toString();job.keyPath=material.key().toString();job.createdAt=Instant.now();
  Files.createDirectories(storage);Files.setPosixFilePermissions(storage,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  Path directory=directory(job.id);Files.createDirectory(directory,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
  try {
   file.transferTo(directory.resolve("original"+extension(job)));
   var data=Map.of("creator",creator,"organization",settings.organizationName(),"profile",settings.profileName(),"configurationRevision",record.activeRevision==null?record.revision:record.activeRevision,"aiDisclosure",ai,"source","user-declared","developmentIdentity",material.development());
   Files.writeString(directory.resolve("manifest.json"),mapper.writeValueAsString(Map.of("claim_generator_info",List.of(Map.of("name","C2PA Trust Portal","version","0.3.0")),"title",title,"format",format,"assertions",List.of(Map.of("label","com.c2pa.portal.declarations","data",data)))));
   return jobs.saveAndFlush(job);
  } catch(Exception e){try(var paths=Files.list(directory)){for(Path p:paths.toList())Files.deleteIfExists(p);}Files.delete(directory);throw e;}
 }
 public String extension(SigningJob job){return ContentFormats.extension(job.format);}
 @EventListener(ApplicationReadyEvent.class) public void recover(){for(var job:jobs.findByStateOrderByCreatedAtAsc("RUNNING",PageRequest.of(0,10000))){job.state="QUEUED";jobs.save(job);}}
 @Scheduled(cron="${portal.jobs.schedule:*/1 * * * * *}") public void process() {
  var pending=jobs.findByStateOrderByCreatedAtAsc("QUEUED",PageRequest.of(0,1));if(pending.isEmpty())return;
  var job=pending.getFirst();job.state="RUNNING";job.attempts++;job=jobs.saveAndFlush(job);Path directory=directory(job.id);Process process=null;
  try {
   Path output=directory.resolve("signed"+extension(job));Files.deleteIfExists(output);
   Path worker=Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize();
   process=new ProcessBuilder(worker.toString(),"sign",directory.resolve("original"+extension(job)).toString(),output.toString(),directory.resolve("manifest.json").toString(),job.certificatePath,job.keyPath).redirectOutput(directory.resolve("report.json").toFile()).redirectError(directory.resolve("worker-error.log").toFile()).start();
   if(!process.waitFor(45,TimeUnit.SECONDS))throw new IllegalStateException("Processing timed out");
   if(process.exitValue()!=0 || !Files.isRegularFile(output))throw new IllegalStateException("Content or certificate could not be signed");
   job.state="COMPLETED";job.error=null;
  } catch(Exception e){job.state="FAILED";job.error="Signing failed. Check content, worker readiness and certificate validity.";}
  finally {
   if(process!=null && process.isAlive()){process.destroyForcibly();try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
   job.completedAt=Instant.now();jobs.saveAndFlush(job);
   AuditEvent event=new AuditEvent();event.createdAt=Instant.now();event.actor=job.owner;event.action="SIGNING_JOB_"+job.state;event.reference=job.id;audit.save(event);
  }
 }
 @Scheduled(cron="${portal.jobs.cleanup.schedule:0 0 * * * *}") public void cleanup() throws Exception {
  int days=retention.findById(1L).map(s->s.retentionDays).orElse(30);
  Instant cutoff=Instant.now().minusSeconds(days*86400L);
  for(String state:List.of("COMPLETED","FAILED","DELETING"))for(SigningJob job:jobs.findByStateOrderByCreatedAtAsc(state,PageRequest.of(0,1000))){
   if(job.completedAt==null || !job.completedAt.isBefore(cutoff))continue;
   job.state="DELETING";job=jobs.saveAndFlush(job);
   Path folder=directory(job.id);if(Files.exists(folder)){try(var files=Files.list(folder)){for(Path file:files.toList())Files.deleteIfExists(file);}Files.delete(folder);}
   jobs.deleteById(job.id);AuditEvent event=new AuditEvent();event.createdAt=Instant.now();event.actor="system";event.action="ASSET_RETENTION_DELETED";event.reference=job.id;audit.save(event);
  }
 }
}
