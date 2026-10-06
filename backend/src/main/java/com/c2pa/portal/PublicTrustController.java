package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;
import java.nio.file.*;
@RestController @RequestMapping("/api/v1/admin/public-trust")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class PublicTrustController {
 public record Version(String id,OfficialPublicTrust.Configuration configuration,Instant fetchedAt,Instant testedAt,OfficialPublicTrust.Summary summary){}
 public record State(Long revision,Version draft,Version active){}
 public record Draft(Long revision,OfficialPublicTrust.Configuration configuration){}
 public record Selection(Long revision,String versionId,boolean acknowledgeOfficialSource){}
 private final PublicTrustSettingsRepository settings;private final PublicTrustVersionRepository versions;private final OfficialPublicTrust trust;private final WorkspaceRepository workspaces;private final ObjectMapper mapper;private final AuditService audit;
 public PublicTrustController(PublicTrustSettingsRepository settings,PublicTrustVersionRepository versions,OfficialPublicTrust trust,WorkspaceRepository workspaces,ObjectMapper mapper,AuditService audit){this.settings=settings;this.versions=versions;this.trust=trust;this.workspaces=workspaces;this.mapper=mapper;this.audit=audit;}
 private PublicTrustSettings settings(){return settings.findById(WorkspaceContext.id()).orElseGet(()->{workspaces.lockById(WorkspaceContext.id()).orElseThrow();return settings.findById(WorkspaceContext.id()).orElseGet(()->{var s=new PublicTrustSettings();s.id=WorkspaceContext.id();return settings.saveAndFlush(s);});});}
 private PublicTrustVersion version(String id){return versions.findById(Objects.requireNonNullElse(id,"")).filter(v->v.workspaceId.equals(WorkspaceContext.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
 private Version view(PublicTrustVersion v)throws Exception{OfficialPublicTrust.Summary summary;try{summary=trust.summary(v);}catch(Exception e){summary=new OfficialPublicTrust.Summary(trust.decode(v.configuration).enabled(),false,0,0,null,null,null);}return new Version(v.id,trust.decode(v.configuration),v.fetchedAt,v.testedAt,summary);}
 private State state(PublicTrustSettings s)throws Exception{return new State(s.revision,s.draftVersion==null?null:view(version(s.draftVersion)),s.activeVersion==null?null:view(version(s.activeVersion)));}
 private PublicTrustSettings current(Long revision){workspaces.lockById(WorkspaceContext.id()).orElseThrow();var s=settings();if(!Objects.equals(s.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Public trust settings changed");return s;}
 @GetMapping @Transactional public State get()throws Exception{return state(settings());}
 @GetMapping("/history") public List<Version> history(@RequestParam(defaultValue="0")int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var rows=new ArrayList<Version>();for(var v:versions.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),PageRequest.of(page,50)))rows.add(view(v));return rows;}
 @PostMapping("/fetch") @Transactional(rollbackFor=Exception.class) public State fetch(@RequestBody Draft request)throws Exception{
  var s=current(request.revision());var v=new PublicTrustVersion();v.id=UUID.randomUUID().toString();v.workspaceId=WorkspaceContext.id();v.createdAt=Instant.now();
  try{v.configuration=mapper.writeValueAsString(trust.validate(request.configuration()));trust.download(v);if(s.highWaterVersion!=null)trust.preventRollback(version(s.highWaterVersion),v);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Official trust-list download failed; verify TLS, trusted proxy, list structure and freshness");}
  versions.saveAndFlush(v);s.draftVersion=v.id;if(trust.decode(v.configuration).enabled())s.highWaterVersion=v.id;settings.saveAndFlush(s);audit.record("OFFICIAL_PUBLIC_TRUST_FETCHED",v.id);return state(s);
 }
 @PostMapping(value="/test",consumes="multipart/form-data") @Transactional(rollbackFor=Exception.class) public State test(@RequestParam Long revision,@RequestParam String versionId,@RequestParam boolean expectPublicTrust,@RequestPart(required=false)MultipartFile file)throws Exception{
  var s=current(revision);var v=version(versionId);var summary=trust.summary(v);if(!summary.current())throw new ResponseStatusException(HttpStatus.CONFLICT,"Refresh official lists first");
  if(summary.enabled()){
   if(file==null || file.isEmpty() || file.getSize()>100*1024*1024L)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);String format=ContentFormats.detect(file);if(format==null)throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
   Path folder=Files.createTempDirectory("c2pa-public-trust-probe-");try{Path input=folder.resolve("sample"+ContentFormats.extension(format));file.transferTo(input);boolean actual=trust.evaluate(v,input,folder).path("publicTrustVerified").asBoolean();if(actual!=expectPublicTrust)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Public trust differs from the expected outcome");}finally{try(var files=Files.walk(folder)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  }else if(expectPublicTrust)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Disabled public trust cannot verify a signer");
  v.testedAt=Instant.now();versions.saveAndFlush(v);audit.record("OFFICIAL_PUBLIC_TRUST_TESTED",v.id+":"+expectPublicTrust);return state(s);
 }
 @PostMapping("/activate") @Transactional public State activate(@RequestBody Selection request)throws Exception{var s=current(request.revision());var v=version(request.versionId());if(!request.acknowledgeOfficialSource() || v.testedAt==null || v.testedAt.isBefore(Instant.now().minusSeconds(86400)) || !trust.summary(v).current())throw new ResponseStatusException(HttpStatus.CONFLICT,"Test the version and acknowledge official-source validation first");if(s.highWaterVersion!=null)trust.preventRollback(version(s.highWaterVersion),v);s.activeVersion=v.id;s.draftVersion=v.id;settings.saveAndFlush(s);audit.record("OFFICIAL_PUBLIC_TRUST_ACTIVATED",v.id);return state(s);}
}
