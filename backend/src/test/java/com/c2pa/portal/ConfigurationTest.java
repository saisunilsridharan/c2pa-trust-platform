package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test"})
@AutoConfigureMockMvc
class ConfigurationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
 @Test void configurationLifecycleAndAccessControl() throws Exception {
  mvc.perform(get("/api/v1/admin/configuration")).andExpect(status().isUnauthorized());
  String token=Files.readString(Path.of(".local/admin-token")).trim();
  var initial=mvc.perform(get("/api/v1/admin/configuration").header("X-Admin-Token",token)).andExpect(status().isOk()).andReturn();
  var revision=mapper.readTree(initial.getResponse().getContentAsString()).get("revision").asLong();
  var settings=Map.of("organizationName","Test Studio","profileName","Editorial","formats",java.util.List.of("image/jpeg"),"maxUploadMb",20,"requireAiDisclosure",true);
  var update=mapper.writeValueAsString(Map.of("settings",settings,"revision",revision));
  var saved=mvc.perform(put("/api/v1/admin/configuration/draft").header("X-Admin-Token",token).contentType("application/json").content(update)).andExpect(status().isOk()).andReturn();
  mvc.perform(put("/api/v1/admin/configuration/draft").header("X-Admin-Token",token).contentType("application/json").content(update)).andExpect(status().isConflict());
  var next=mapper.readTree(saved.getResponse().getContentAsString()).get("revision").asLong();
  mvc.perform(post("/api/v1/admin/configuration/draft/activate").header("X-Admin-Token",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",next)))).andExpect(status().isOk()).andExpect(jsonPath("$.active.organizationName").value("Test Studio")).andExpect(jsonPath("$.signingAvailable").value(false));
  mvc.perform(post("/api/v1/admin/configuration/draft/test").header("X-Admin-Token",token).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/v1/verification").file(new org.springframework.mock.web.MockMultipartFile("file","text.jpg","image/jpeg","not-an-image".getBytes())).header("X-Admin-Token",token)).andExpect(status().isUnsupportedMediaType());
  mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.components.securitySchemes.adminToken.name").value("X-Admin-Token"));
 }
}
