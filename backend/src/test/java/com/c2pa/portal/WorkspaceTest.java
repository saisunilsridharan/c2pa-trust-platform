package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workspace-test","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-"})
@AutoConfigureMockMvc
class WorkspaceTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JobRepository jobs;@Autowired UserRepository users;@Autowired MembershipRepository memberships;@Autowired WorkspaceService service;
 @MockitoBean DevelopmentIdentity identity;
 String json(Object value)throws Exception{return mapper.writeValueAsString(value);}
 JsonNode body(org.springframework.test.web.servlet.MvcResult result)throws Exception{return mapper.readTree(result.getResponse().getContentAsString());}
 String login(String name)throws Exception{return body(mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json(Map.of("username",name,"password","workspace-password-123")))).andExpect(status().isOk()).andReturn()).get("token").asText();}
 JsonNode activate(String token,Long workspace,String organization)throws Exception{
  var state=body(mvc.perform(get("/api/v1/admin/configuration").header("X-Admin-Token",token).header("X-Workspace-Id",workspace)).andExpect(status().isOk()).andReturn());
  var settings=Map.of("organizationName",organization,"profileName","Creator","formats",List.of("image/png"),"maxUploadMb",25,"requireAiDisclosure",true);
  var saved=body(mvc.perform(put("/api/v1/admin/configuration/draft").header("X-Admin-Token",token).header("X-Workspace-Id",workspace).contentType("application/json").content(json(Map.of("settings",settings,"revision",state.get("revision").asLong())))).andExpect(status().isOk()).andReturn());
  return body(mvc.perform(post("/api/v1/admin/configuration/draft/activate").header("X-Admin-Token",token).header("X-Workspace-Id",workspace).contentType("application/json").content(json(Map.of("revision",saved.get("revision").asLong())))).andExpect(status().isOk()).andReturn());
 }
 @Test void membershipConfigurationAssetsAndHistoryAreIsolated()throws Exception {
  when(identity.status()).thenReturn(new DevelopmentIdentity.Status(false,false,"NOT_CONFIGURED","development",false,null,null));
  String bootstrap=Files.readString(Path.of(".local/admin-token")).trim();
  mvc.perform(post("/api/v1/auth/enroll").header("X-Admin-Token",bootstrap).contentType("application/json").content(json(Map.of("username","platform","password","workspace-password-123")))).andExpect(status().isOk());String platform=login("platform");
  for(String name:List.of("alice","bob"))mvc.perform(post("/api/v1/admin/users").header("X-Admin-Token",platform).contentType("application/json").content(json(Map.of("username",name,"password","workspace-password-123","role","SIGNER")))).andExpect(status().isOk());
  Long studio=body(mvc.perform(post("/api/v1/workspaces").header("X-Admin-Token",platform).contentType("application/json").content(json(Map.of("name","Private studio")))).andExpect(status().isOk()).andReturn()).get("id").asLong();assertNotEquals(1L,studio);
  mvc.perform(put("/api/v1/workspaces/"+studio+"/members").header("X-Admin-Token",platform).contentType("application/json").content(json(Map.of("username","alice","role","ADMIN")))).andExpect(status().isOk());
  mvc.perform(put("/api/v1/workspaces/"+studio).header("X-Admin-Token",platform).contentType("application/json").content(json(Map.of("name","Renamed studio","revision",0)))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed studio"));
  mvc.perform(put("/api/v1/workspaces/"+studio).header("X-Admin-Token",platform).contentType("application/json").content(json(Map.of("name","Stale rename","revision",0)))).andExpect(status().isConflict());
  String alice=login("alice"),bob=login("bob");
  mvc.perform(get("/api/v1/portal/configuration").header("X-Admin-Token",bob).header("X-Workspace-Id",studio)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/admin/configuration").header("X-Admin-Token",alice).header("X-Workspace-Id",1)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(jsonPath("$.role").value("ADMIN")).andExpect(jsonPath("$.platformAdministrator").value(false));
  mvc.perform(get("/api/v1/admin/users").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/admin/security").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(status().isForbidden());
  activate(platform,1L,"Default private organization");var second=activate(alice,studio,"Studio private organization");
  mvc.perform(get("/api/v1/portal/configuration").header("X-Admin-Token",platform).header("X-Workspace-Id",1)).andExpect(jsonPath("$.active.organizationName").value("Default private organization"));
  mvc.perform(get("/api/v1/admin/configuration/history").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].settings.organizationName").value("Studio private organization"));
  var foreign=body(mvc.perform(get("/api/v1/admin/configuration/history").header("X-Admin-Token",platform).header("X-Workspace-Id",1)).andReturn()).get(0).get("id").asLong();
  mvc.perform(post("/api/v1/admin/configuration/rollback").header("X-Admin-Token",alice).header("X-Workspace-Id",studio).contentType("application/json").content(json(Map.of("versionId",foreign,"revision",second.get("revision").asLong())))).andExpect(status().isNotFound());
  SigningJob job=new SigningJob();job.id=UUID.randomUUID().toString();job.owner="bob";job.workspaceId=studio;job.state="COMPLETED";job.title="Studio confidential";job.format="image/png";job.createdAt=java.time.Instant.now();jobs.saveAndFlush(job);
  mvc.perform(get("/api/v1/jobs/"+job.id+"/download").header("X-Admin-Token",bob).header("X-Workspace-Id",1)).andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/jobs").header("X-Admin-Token",bob).header("X-Workspace-Id",1)).andExpect(jsonPath("$.length()").value(0));
  mvc.perform(get("/api/v1/jobs").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(jsonPath("$[0].id").value(job.id));
  mvc.perform(get("/api/v1/admin/operations").header("X-Admin-Token",platform).header("X-Workspace-Id",1)).andExpect(jsonPath("$.completed").value(0));
  mvc.perform(get("/api/v1/admin/operations").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(jsonPath("$.completed").value(1));
  mvc.perform(get("/api/v1/admin/audit-events").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(jsonPath("$[0].workspaceId").value(studio.intValue()));
  mvc.perform(get("/api/v1/admin/storage").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(status().isOk());
  var platformMembership=memberships.findByUserIdAndWorkspaceId(users.findByUsername("platform").orElseThrow().id,studio).orElseThrow();
  mvc.perform(delete("/api/v1/workspaces/"+studio+"/members/"+platformMembership.userId).param("revision",platformMembership.revision.toString()).header("X-Admin-Token",platform)).andExpect(status().isOk());
  var aliceId=users.findByUsername("alice").orElseThrow().id;var aliceMembership=memberships.findByUserIdAndWorkspaceId(aliceId,studio).orElseThrow();
  mvc.perform(put("/api/v1/workspaces/"+studio+"/members").header("X-Admin-Token",alice).header("X-Workspace-Id",studio).contentType("application/json").content(json(Map.of("username","alice","role","VIEWER","revision",aliceMembership.revision)))).andExpect(status().isConflict());
  mvc.perform(delete("/api/v1/workspaces/"+studio+"/members/"+aliceId).param("revision",aliceMembership.revision.toString()).header("X-Admin-Token",platform)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/portal/configuration").header("X-Admin-Token",alice).header("X-Workspace-Id",studio)).andExpect(status().isForbidden());
  var defaultMembership=memberships.findByUserIdAndWorkspaceId(aliceId,1L).orElseThrow();mvc.perform(delete("/api/v1/workspaces/1/members/"+aliceId).param("revision",defaultMembership.revision.toString()).header("X-Admin-Token",platform)).andExpect(status().isOk());
  service.initialize();assertFalse(memberships.existsByUserId(aliceId));
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",alice)).andExpect(jsonPath("$.workspaceId").value(0));
  mvc.perform(get("/api/v1/workspaces").header("X-Admin-Token",alice)).andExpect(jsonPath("$.length()").value(0));
 }
}
