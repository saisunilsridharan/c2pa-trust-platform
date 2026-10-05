package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:storage-versions","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-"})
@AutoConfigureMockMvc
class StorageProvidersTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired StorageVersionRepository versions;
 @Test void testBeforeActivationConflictHistoryAndCrossWorkspaceDenial()throws Exception {
  String token=Files.readString(Path.of(".local/admin-token")).trim();String base="/api/v1/admin/storage/providers";
  mvc.perform(get(base)).andExpect(status().isUnauthorized());
  long revision=mapper.readTree(mvc.perform(get(base).header("X-Admin-Token",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("revision").asLong();
  var draft=mapper.writeValueAsString(Map.of("revision",revision,"configuration",Map.of("provider","LOCAL")));
  var saved=mapper.readTree(mvc.perform(put(base+"/draft").header("X-Admin-Token",token).contentType("application/json").content(draft)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  mvc.perform(put(base+"/draft").header("X-Admin-Token",token).contentType("application/json").content(draft)).andExpect(status().isConflict());
  String selected=mapper.writeValueAsString(Map.of("revision",saved.get("revision").asLong(),"versionId",saved.get("draft").get("id").asText(),"acknowledgeActivation",true));
  mvc.perform(post(base+"/activate").header("X-Admin-Token",token).contentType("application/json").content(selected)).andExpect(status().isConflict());
  mvc.perform(post(base+"/test").header("X-Admin-Token",token).contentType("application/json").content(selected)).andExpect(status().isOk()).andExpect(jsonPath("$.draft.testedAt").isNotEmpty());
  mvc.perform(post(base+"/activate").header("X-Admin-Token",token).contentType("application/json").content(selected)).andExpect(status().isOk()).andExpect(jsonPath("$.active.configuration.provider").value("LOCAL"));
  mvc.perform(post(base+"/activate").header("X-Admin-Token",token).contentType("application/json").content(selected)).andExpect(status().isConflict());
  mvc.perform(get(base+"/history").header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
  var other=new StorageVersion();other.id=UUID.randomUUID().toString();other.workspaceId=2L;other.configuration="{\"provider\":\"LOCAL\"}";other.createdAt=java.time.Instant.now();versions.saveAndFlush(other);
  long current=mapper.readTree(mvc.perform(get(base).header("X-Admin-Token",token)).andReturn().getResponse().getContentAsString()).get("revision").asLong();
  mvc.perform(post(base+"/test").header("X-Admin-Token",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",current,"versionId",other.id)))).andExpect(status().isNotFound());
 }
}
