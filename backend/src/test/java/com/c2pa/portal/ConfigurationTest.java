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
 @org.springframework.test.context.bean.override.mockito.MockitoBean DevelopmentIdentity identity;
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
 @Test void configurationLifecycleAndAccessControl() throws Exception {
  org.mockito.Mockito.when(identity.status()).thenReturn(new DevelopmentIdentity.Status(false,false,"NOT_CONFIGURED","development",false,null,null));
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
  var history=mvc.perform(get("/api/v1/admin/configuration/history").header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].settings.organizationName").value("Test Studio")).andReturn();
  var versionId=mapper.readTree(history.getResponse().getContentAsString()).get(0).get("id").asLong();
  var current=mapper.readTree(mvc.perform(get("/api/v1/admin/configuration").header("X-Admin-Token",token)).andReturn().getResponse().getContentAsString());
  var changed=Map.of("organizationName","Changed Studio","profileName","Editorial","formats",java.util.List.of("image/jpeg"),"maxUploadMb",20,"requireAiDisclosure",true);
  var changedResult=mvc.perform(put("/api/v1/admin/configuration/draft").header("X-Admin-Token",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("settings",changed,"revision",current.get("revision").asLong())))).andReturn();
  var changedRevision=mapper.readTree(changedResult.getResponse().getContentAsString()).get("revision").asLong();
  var activated=mvc.perform(post("/api/v1/admin/configuration/draft/activate").header("X-Admin-Token",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",changedRevision)))).andReturn();
  var activeRevision=mapper.readTree(activated.getResponse().getContentAsString()).get("revision").asLong();
  var rollback=mapper.writeValueAsString(Map.of("versionId",versionId,"revision",activeRevision));
  mvc.perform(post("/api/v1/admin/configuration/rollback").header("X-Admin-Token",token).contentType("application/json").content(rollback)).andExpect(status().isOk()).andExpect(jsonPath("$.active.organizationName").value("Test Studio")).andExpect(jsonPath("$.draft.organizationName").value("Test Studio"));
  mvc.perform(post("/api/v1/admin/configuration/rollback").header("X-Admin-Token",token).contentType("application/json").content(rollback)).andExpect(status().isConflict());
  mvc.perform(get("/api/v1/admin/audit-events").header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].action").value("CONFIGURATION_ROLLED_BACK"));
  mvc.perform(get("/api/v1/admin/audit-events")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/admin/configuration/draft/test").header("X-Admin-Token",token).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/v1/verification").file(new org.springframework.mock.web.MockMultipartFile("file","text.jpg","image/jpeg","not-an-image".getBytes())).header("X-Admin-Token",token)).andExpect(status().isUnsupportedMediaType());
  mvc.perform(post("/api/v1/admin/signing-identity/development").header("X-Admin-Token",token).contentType("application/json").content("{\"acknowledgeUntrusted\":false}")).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/v1/signing").file(new org.springframework.mock.web.MockMultipartFile("file","sample.png","image/png",new byte[]{1})).param("creator","Creator").param("title","Title").param("acknowledgePublicClaims","false").header("X-Admin-Token",token)).andExpect(status().isBadRequest());
  mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.components.securitySchemes.adminToken.name").value("X-Admin-Token"));
 }
}
