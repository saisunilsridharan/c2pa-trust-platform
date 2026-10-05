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
 private final JobRepository jobs;private final ConfigurationRepository configs;private final DevelopmentIdentity identity;private final ObjectMapper mapper;private final AuditService audit;private final StorageRepository retention;
 private final Path storage=Path.of(".local/assets").toAbsolutePath();
 private final StorageVersionRepository storageVersions;private final PrivateObjectStorage objects;
 private final JobCompletion completion;
 private final WorkerExecution execution;private final SigningChoices choices;private final JobLeases leases;private final ProcessingRepository processing;
 public JobService(JobRepository jobs,ConfigurationRepository configs,DevelopmentIdentity identity,ObjectMapper mapper,AuditService audit,StorageRepository retention,StorageVersionRepository storageVersions,PrivateObjectStorage objects,JobCompletion completion,JobLeases leases,ProcessingRepository processing,SigningChoices choices,WorkerExecution execution){this.execution=execution;this.choices=choices;this.jobs=jobs;this.configs=configs;this.identity=identity;this.mapper=mapper;this.audit=audit;this.retention=retention;this.storageVersions=storageVersions;this.objects=objects;this.completion=completion;this.leases=leases;this.processing=processing;}
 public Path directory(String id){UUID.fromString(id);return storage.resolve(id);}
 public synchronized SigningJob submit(MultipartFile file,String creator,String title,String ai,String owner,String requestId,Long expectedProfileRevision,String expectedIdentityFingerprint) throws Exception {
  return submit(file,creator,title,ai,owner,requestId,expectedProfileRevision,expectedIdentityFingerprint,null,null);
 }
 public synchronized SigningJob submit(MultipartFile file,String creator,String title,String ai,String owner,String requestId,Long expectedProfileRevision,String expectedIdentityFingerprint,String optionId,Long optionRevision) throws Exception {
  if(creator.isBlank() || creator.length()>120 || title.isBlank() || title.length()>200 || !Set.of("none","generated","edited","unspecified").contains(ai))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid public claims");
  if(!requestId.matches("[a-zA-Z0-9-]{1,80}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request identifier");
  String requestKey=WorkspaceContext.id()+":"+owner+":"+requestId;
  MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=file.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
  String contentHash=HexFormat.of().formatHex(digest.digest());
  digest.update((contentHash+mapper.writeValueAsString(List.of(creator,title,ai))+(optionId==null || optionId.isBlank()?"":mapper.writeValueAsString(List.of(optionId,Objects.requireNonNullElse(optionRevision,-1L),Objects.requireNonNullElse(expectedProfileRevision,-1L),Objects.requireNonNullElse(expectedIdentityFingerprint,""))))).getBytes(java.nio.charset.StandardCharsets.UTF_8));String requestDigest=HexFormat.of().formatHex(digest.digest());
  var existing=jobs.findByRequestKey(requestKey);if(existing.isEmpty() && WorkspaceContext.id().equals(1L))existing=jobs.findByRequestKey(owner+":"+requestId);if(existing.isPresent()){if(!Objects.equals(existing.get().requestDigest,requestDigest))throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key already used for different content or claims");return existing.get();}
  var selected=choices.select(optionId,optionRevision);
  var settings=selected.settings();
  if(settings.requireAiDisclosure() && ai.equals("unspecified"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AI declaration required");
  if(file.isEmpty() || file.getSize()>settings.maxUploadMb()*1024L*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File exceeds profile limits");
  String format=ContentFormats.detect(file);
  if(format==null || !settings.formats().contains(format))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Unsupported or disabled format");
  var material=selected.material();if(!Objects.equals(expectedProfileRevision,selected.profileRevision()) || !Objects.equals(expectedIdentityFingerprint,material.fingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Profile or identity changed; reload and review public claims again");SigningJob job=new SigningJob();job.id=UUID.randomUUID().toString();job.owner=owner;job.workspaceId=WorkspaceContext.id();job.requestKey=requestKey;job.requestDigest=requestDigest;job.state="QUEUED";job.format=format;job.title=title;job.fingerprint=material.fingerprint();job.certificatePath=material.certificate().toString();job.keyPath=material.key().toString();job.createdAt=Instant.now();
  Files.createDirectories(storage);Files.setPosixFilePermissions(storage,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  Path directory=directory(job.id);Files.createDirectory(directory,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
  try {
   processing.findById(job.workspaceId).ifPresent(p->{job.workerTimeoutSeconds=p.workerTimeoutSeconds;job.maxAttempts=p.maxAttempts;});
   var activeStorage=retention.findById(job.workspaceId).map(s->s.activeVersion).orElse(null);
   if(activeStorage!=null)job.storageSnapshot=storageVersions.findById(activeStorage).filter(v->v.workspaceId.equals(job.workspaceId)).orElseThrow().configuration;
   file.transferTo(directory.resolve("original"+extension(job)));
   var data=new LinkedHashMap<String,Object>(Map.of("creator",creator,"organization",settings.organizationName(),"profile",settings.profileName(),"configurationRevision",selected.profileRevision(),"aiDisclosure",ai,"source","user-declared","developmentIdentity",material.development()));
   if(selected.optionId()!=null){data.put("signingChoiceId",selected.optionId());data.put("signingChoiceRevision",selected.optionRevision());}
   Files.writeString(directory.resolve("manifest.json"),mapper.writeValueAsString(Map.of("claim_generator_info",List.of(Map.of("name","C2PA Trust Portal","version","0.3.0")),"title",title,"format",format,"assertions",List.of(Map.of("label","com.c2pa.portal.declarations","data",data)))));
   objects.write(job,"original"+extension(job),directory.resolve("original"+extension(job)));
   return jobs.saveAndFlush(job);
  } catch(Exception e){try{objects.delete(job);}catch(Exception cleanup){e.addSuppressed(cleanup);}try(var paths=Files.list(directory)){for(Path p:paths.toList())Files.deleteIfExists(p);}Files.delete(directory);throw e;}
 }
 public String extension(SigningJob job){return ContentFormats.extension(job.format);}
 @EventListener(ApplicationReadyEvent.class) @Scheduled(cron="${portal.jobs.recovery.schedule:*/10 * * * * *}") public void recover(){for(var job:jobs.findByStateOrderByCreatedAtAsc("RUNNING",PageRequest.of(0,10000)))leases.recover(job.id);}
 @Scheduled(cron="${portal.jobs.schedule:*/1 * * * * *}") public void process() {
  var pending=jobs.findByStateOrderByCreatedAtAsc("QUEUED",PageRequest.of(0,1));if(pending.isEmpty())return;
  var claimed=leases.claim(pending.getFirst().id);if(claimed.isEmpty())return;var job=claimed.get();job.resultAttempt=job.leaseToken;Path source=directory(job.id),directory=source.resolve("attempts").resolve(job.resultAttempt);Process process=null;
  try {
   Files.createDirectories(directory);Path output=directory.resolve("signed"+extension(job));
   Path worker=Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize();
   int exit=execution.sign(job.workspaceId,worker,source.resolve("original"+extension(job)),output,source.resolve("manifest.json"),Path.of(job.certificatePath),job.keyPath,directory.resolve("report.json"),directory.resolve("worker-error.log"),job.workerTimeoutSeconds);
   if(exit!=0 || !Files.isRegularFile(output))throw new IllegalStateException("Content or certificate could not be signed");
   objects.write(job,"signed"+extension(job),output);objects.write(job,"report.json",directory.resolve("report.json"));
   job.state="COMPLETED";job.error=null;
  } catch(Exception e){job.state="FAILED";job.error="Signing failed. Check content, worker readiness and certificate validity.";}
  finally {
   if(process!=null && process.isAlive()){process.destroyForcibly();try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
   job.completedAt=Instant.now();completion.finish(job);
  }
 }
 @Scheduled(cron="${portal.jobs.cleanup.schedule:0 0 * * * *}") public void cleanup() throws Exception {
  for(String state:List.of("COMPLETED","FAILED","DELETING"))for(SigningJob job:jobs.findByStateOrderByCreatedAtAsc(state,PageRequest.of(0,1000))){
   int days=retention.findById(job.workspaceId).map(s->s.retentionDays).orElse(30);Instant cutoff=Instant.now().minusSeconds(days*86400L);
   if(job.completedAt==null || !job.completedAt.isBefore(cutoff))continue;
   job.state="DELETING";job=jobs.saveAndFlush(job);
   objects.delete(job);
   Path folder=directory(job.id);if(Files.exists(folder)){try(var files=Files.walk(folder)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
   jobs.deleteById(job.id);audit.record(job.workspaceId,"system","ASSET_RETENTION_DELETED",job.id);
  }
 }
 public byte[] readAsset(SigningJob job,String name,long limit)throws Exception{
  if(objects.remote(job))return objects.read(job,name,limit);
  Path folder=directory(job.id);if(!name.startsWith("original") && job.resultAttempt!=null)folder=folder.resolve("attempts").resolve(job.resultAttempt);Path file=folder.resolve(name);if(!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(Files.size(file)>limit)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Asset exceeds download limit");return Files.readAllBytes(file);
 }
}
