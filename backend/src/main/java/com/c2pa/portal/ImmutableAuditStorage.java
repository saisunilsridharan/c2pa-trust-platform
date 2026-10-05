package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Service public class ImmutableAuditStorage {
 public record Configuration(boolean enabled,PrivateObjectStorage.Configuration storage,int retentionDays,int intervalMinutes){}
 public record Receipt(String format,String bucket,String key,String versionId,Instant retainUntil,String sha256,AuditIntegrityController.Checkpoint checkpoint){}
 private final PrivateObjectStorage storage;private final ObjectMapper mapper;
 public ImmutableAuditStorage(PrivateObjectStorage storage,ObjectMapper mapper){this.storage=storage;this.mapper=mapper;}
 public Configuration validate(Long workspace,Configuration c)throws Exception{
  if(c==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Audit storage configuration is required");
  if(!c.enabled())return new Configuration(false,null,1,60);
  if(c.retentionDays()<1 || c.retentionDays()>3650 || c.intervalMinutes()<5 || c.intervalMinutes()>1440)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Retention must be 1–3650 days and checkpoint interval 5–1440 minutes");
  var provider=storage.validate(workspace,c.storage());if(!provider.provider().equals("S3"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Immutable audit storage requires a versioned S3 Object Lock bucket");
  return new Configuration(true,provider,c.retentionDays(),c.intervalMinutes());
 }
 public Configuration decode(String json)throws Exception{return mapper.readValue(json,Configuration.class);}
 private void bucket(software.amazon.awssdk.services.s3.S3Client client,Configuration c){
  var lock=client.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder().bucket(c.storage().bucket()).build());
  var versioning=client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(c.storage().bucket()).build());
  if(lock.objectLockConfiguration()==null || lock.objectLockConfiguration().objectLockEnabled()!=ObjectLockEnabled.ENABLED || versioning.status()!=BucketVersioningStatus.ENABLED)throw new IllegalStateException("Versioning and Object Lock must be enabled");
 }
 private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 public Receipt write(Long workspace,Configuration c,String id,String checkpoint)throws Exception{
  var provider=c.storage();byte[] payload=checkpoint.getBytes(StandardCharsets.UTF_8);if(payload.length>4096)throw new IllegalArgumentException();
  var parsed=mapper.readValue(checkpoint,AuditIntegrityController.Checkpoint.class);if(!Objects.equals(parsed.workspaceId(),workspace))throw new IllegalArgumentException();
  Instant until=Instant.now().plusSeconds(c.retentionDays()*86400L).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);String key=provider.prefix()+"/workspace-"+workspace+"/audit-checkpoints/"+UUID.fromString(id)+".json";
  try(var client=storage.client(workspace,provider)){
   bucket(client,c);
   var result=client.putObject(PutObjectRequest.builder().bucket(provider.bucket()).key(key).contentType("application/json").checksumSHA256(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload))).objectLockMode(ObjectLockMode.COMPLIANCE).objectLockRetainUntilDate(until).build(),RequestBody.fromBytes(payload));
   String version=result.versionId();if(version==null || version.isBlank() || version.equals("null") || version.length()>1024)throw new IllegalStateException("Version-specific receipt is required");
   var receipt=new Receipt("c2pa-audit-object-lock-v1",provider.bucket(),key,version,until,hash(payload),parsed);
   verify(client,receipt,payload);return receipt;
  }
 }
 private void verify(software.amazon.awssdk.services.s3.S3Client client,Receipt receipt,byte[] expected)throws Exception{
  var retention=client.getObjectRetention(GetObjectRetentionRequest.builder().bucket(receipt.bucket()).key(receipt.key()).versionId(receipt.versionId()).build()).retention();
  if(retention==null || retention.mode()!=ObjectLockRetentionMode.COMPLIANCE || retention.retainUntilDate()==null || retention.retainUntilDate().isBefore(receipt.retainUntil()))throw new IllegalStateException("Compliance retention not confirmed");
  try(var input=client.getObject(GetObjectRequest.builder().bucket(receipt.bucket()).key(receipt.key()).versionId(receipt.versionId()).build())){
   if(input.response().contentLength()>4096)throw new IllegalStateException();byte[] found=input.readNBytes(4097);if(found.length>4096 || !MessageDigest.isEqual(expected,found) || !hash(found).equals(receipt.sha256()))throw new IllegalStateException("Stored checkpoint differs");
  }
 }
 public void verify(Long workspace,Configuration c,Receipt receipt,String checkpoint)throws Exception{
  if(!c.enabled() || !receipt.bucket().equals(c.storage().bucket()) || !receipt.key().startsWith(c.storage().prefix()+"/workspace-"+workspace+"/audit-checkpoints/"))throw new IllegalArgumentException();
  try(var client=storage.client(workspace,c.storage())){verify(client,receipt,checkpoint.getBytes(StandardCharsets.UTF_8));}
 }
 public void test(Long workspace,Configuration c)throws Exception{
  if(!c.enabled())return;
  String probe=mapper.writeValueAsString(new AuditIntegrityController.Checkpoint("c2pa-audit-v1",workspace,0,AuditHasher.GENESIS,Instant.now()));
  var receipt=write(workspace,c,UUID.randomUUID().toString(),probe);
  try(var client=storage.client(workspace,c.storage())){
   boolean denied=false;try{client.deleteObject(DeleteObjectRequest.builder().bucket(receipt.bucket()).key(receipt.key()).versionId(receipt.versionId()).build());}catch(S3Exception e){denied=e.statusCode()==403;}
   if(!denied)throw new IllegalStateException("Protected probe deletion was not refused");verify(client,receipt,probe.getBytes(StandardCharsets.UTF_8));
  }
 }
}
