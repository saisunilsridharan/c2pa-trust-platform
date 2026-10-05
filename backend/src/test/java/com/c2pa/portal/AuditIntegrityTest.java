package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:audit-integrity","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-","portal.jobs.recovery.schedule=-","portal.webhooks.schedule=-"})
@AutoConfigureMockMvc
class AuditIntegrityTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired AuditService audit;@Autowired AuditRepository events;@Autowired AuditHeadRepository heads;
 @Test void legacyImportContentChangesAndExternalCheckpointDetectRewrite()throws Exception{
  var legacy=new AuditEvent();legacy.createdAt=java.time.Instant.parse("2020-01-01T00:00:00Z");legacy.actor="old-actor";legacy.action="OLD_EVENT";events.saveAndFlush(legacy);audit.record("NEW_EVENT","reference");String token=Files.readString(Path.of(".local/admin-token")).trim(),base="/api/v1/admin/audit-integrity";
  mvc.perform(get(base).header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.legacyImported").value(1));String checkpoint=mvc.perform(get(base+"/checkpoint").header("X-Admin-Token",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();audit.record("LATER_EVENT","next");
  mvc.perform(post(base+"/checkpoint/verify").header("X-Admin-Token",token).contentType("application/json").content(checkpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
  var changed=events.findByWorkspaceIdAndChainIndex(1L,1L).orElseThrow();changed.actor="changed-actor";events.saveAndFlush(changed);mvc.perform(get(base).header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));mvc.perform(get(base+"/checkpoint").header("X-Admin-Token",token)).andExpect(status().isConflict());
  String hash=AuditHasher.GENESIS;for(var event:events.findByWorkspaceIdOrderByIdAsc(1L,org.springframework.data.domain.PageRequest.of(0,100))){event.previousHash=hash;event.hash=AuditHasher.hash(event);hash=event.hash;events.saveAndFlush(event);}var head=heads.findById(1L).orElseThrow();head.hash=hash;heads.saveAndFlush(head);
  mvc.perform(get(base).header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));mvc.perform(post(base+"/checkpoint/verify").header("X-Admin-Token",token).contentType("application/json").content(checkpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
  events.delete(events.findByWorkspaceIdAndChainIndex(1L,3L).orElseThrow());mvc.perform(get(base).header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
 }
}
