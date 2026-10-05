package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import java.nio.file.*;
import java.util.*;
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
@RestController
@RequestMapping("/api/v1/jobs")
public class JobsController {
 private final JobRepository jobs;private final JobService service;private final AuditService audit;
 public JobsController(JobRepository jobs,JobService service,AuditService audit){this.jobs=jobs;this.service=service;this.audit=audit;}
 public record Job(String id,String state,String title,String format,String owner,int attempts,String error,java.time.Instant createdAt){}
 private Job view(SigningJob j){return new Job(j.id,j.state,j.title,j.format,j.owner,j.attempts,j.error,j.createdAt);}
 private SigningJob authorized(String id,HttpServletRequest request){var job=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!job.workspaceId.equals(WorkspaceContext.id()) || (!request.getAttribute("portal.role").equals("ADMIN") && !job.owner.equals(request.getAttribute("portal.actor"))))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return job;}
 @PostMapping(consumes="multipart/form-data") public Job submit(@RequestPart MultipartFile file,@RequestParam String creator,@RequestParam String title,@RequestParam String aiDisclosure,@RequestParam boolean acknowledgePublicClaims,@RequestHeader("Idempotency-Key") String key,@RequestParam Long expectedProfileRevision,@RequestParam String expectedIdentityFingerprint,HttpServletRequest request)throws Exception{
  if(!acknowledgePublicClaims)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Review public claims first");
  var job=service.submit(file,creator,title,aiDisclosure,(String)request.getAttribute("portal.actor"),key,expectedProfileRevision,expectedIdentityFingerprint);audit.record("SIGNING_JOB_SUBMITTED",job.id);return view(job);
 }
 @GetMapping public List<Job> list(@RequestParam(defaultValue="0") int page,HttpServletRequest request){if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var paging=PageRequest.of(page,50);return (request.getAttribute("portal.role").equals("ADMIN")?jobs.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),paging):jobs.findByWorkspaceIdAndOwnerOrderByCreatedAtDesc(WorkspaceContext.id(),(String)request.getAttribute("portal.actor"),paging)).stream().map(this::view).toList();}
 @PostMapping("/{id}/retry") public Job retry(@PathVariable String id,HttpServletRequest request){var job=authorized(id,request);if(!job.state.equals("FAILED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed jobs can be retried");job.state="QUEUED";job.error=null;jobs.saveAndFlush(job);audit.record("SIGNING_JOB_RETRIED",id);return view(job);}
 @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id,@RequestParam(defaultValue="signed") String version,HttpServletRequest request)throws Exception{
  var job=authorized(id,request);if(job.state.equals("DELETING"))throw new ResponseStatusException(HttpStatus.GONE,"Asset retention expired");if(!Set.of("original","signed").contains(version))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);if(version.equals("signed") && !job.state.equals("COMPLETED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Signed output is not ready");
  byte[] bytes=service.readAsset(job,version+service.extension(job),128*1024*1024L);audit.record("ASSET_DOWNLOADED",id+":"+version);return ResponseEntity.ok().contentType(MediaType.parseMediaType(job.format)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+version+service.extension(job)+"\"").body(bytes);
 }
 @GetMapping("/{id}/report") public ResponseEntity<byte[]> report(@PathVariable String id,HttpServletRequest request)throws Exception {
  var job=authorized(id,request);if(!job.state.equals("COMPLETED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Verification report is not ready");
  byte[] bytes=service.readAsset(job,"report.json",8*1024*1024L);
  audit.record("REPORT_DOWNLOADED",id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"verification-report.json\"").body(bytes);
 }

}
