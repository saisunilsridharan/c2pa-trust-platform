package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:jobs-test","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-"})
class JobTest {
 @Autowired JobService service;@Autowired JobRepository jobs;@Autowired ConfigurationRepository configs;
 @MockitoBean DevelopmentIdentity identity;
 @Test void persistenceIdempotencyAndSnapshot()throws Exception{
  ConfigurationRecord record=new ConfigurationRecord();record.id=1L;record.active="{\"organizationName\":\"Studio\",\"profileName\":\"Creator\",\"formats\":[\"image/png\"],\"maxUploadMb\":25,\"requireAiDisclosure\":true}";record.activeRevision=1L;configs.saveAndFlush(record);
  when(identity.material()).thenReturn(new DevelopmentIdentity.Material(Path.of("/no-key/cert.pem"),Path.of("/no-key/key.pem"),"test-fingerprint"));
  byte[] bytes={(byte)137,80,78,71,13,10,26,10};var file=new MockMultipartFile("file","test.png","image/png",bytes);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.submit(file,"Creator","Title","none","test-owner",UUID.randomUUID().toString(),2L,"test-fingerprint"));
  String request=UUID.randomUUID().toString();SigningJob job=service.submit(file,"Creator","Title","none","test-owner",request,1L,"test-fingerprint");
  try {
   assertEquals(job.id,service.submit(file,"Creator","Title","none","test-owner",request,1L,"test-fingerprint").id);
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.submit(file,"Different","Title","none","test-owner",request,1L,"test-fingerprint"));
   assertTrue(Files.exists(service.directory(job.id).resolve("original.png")));
   record.active="{\"organizationName\":\"Changed\"}";configs.saveAndFlush(record);
   assertTrue(Files.readString(service.directory(job.id).resolve("manifest.json")).contains("Studio"));
   var running=jobs.findById(job.id).orElseThrow();running.state="RUNNING";jobs.saveAndFlush(running);service.recover();assertEquals("QUEUED",jobs.findById(job.id).orElseThrow().state);
   service.process();var failed=jobs.findById(job.id).orElseThrow();assertEquals("FAILED",failed.state);assertEquals(1,failed.attempts);
   failed.completedAt=java.time.Instant.now().minusSeconds(31*86400L);jobs.saveAndFlush(failed);service.cleanup();assertFalse(jobs.existsById(job.id));assertFalse(Files.exists(service.directory(job.id)));
  } finally {
   if(jobs.existsById(job.id))jobs.deleteById(job.id);if(Files.exists(service.directory(job.id))){try(var paths=Files.list(service.directory(job.id))){for(Path p:paths.toList())Files.deleteIfExists(p);}Files.deleteIfExists(service.directory(job.id));}
  }
 }
}
