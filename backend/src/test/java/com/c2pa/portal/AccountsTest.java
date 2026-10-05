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
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:accounts-test","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-"})
@AutoConfigureMockMvc
class AccountsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired UserRepository users;@Autowired JobRepository jobs;
 String json(Object body)throws Exception{return mapper.writeValueAsString(body);}
 String login(String username)throws Exception{return login(username,"testing-password-123");}
 String login(String username,String password)throws Exception{
  var response=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn();
  return mapper.readTree(response.getResponse().getContentAsString()).get("token").asText();
 }
 @Test void enrollmentPermissionsLogoutAndLastAdministrator() throws Exception{
  String bootstrap=Files.readString(Path.of(".local/admin-token")).trim();
  mvc.perform(post("/api/v1/auth/enroll").contentType("application/json").content(json(Map.of("username","administrator","password","testing-password-123")))).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/auth/enroll").header("X-Admin-Token",bootstrap).contentType("application/json").content(json(Map.of("username","administrator","password","testing-password-123")))).andExpect(status().isOk());
  assertNotEquals("testing-password-123",users.findByUsername("administrator").orElseThrow().passwordHash);
  mvc.perform(get("/api/v1/admin/users").header("X-Admin-Token",bootstrap)).andExpect(status().isUnauthorized());
  String admin=login("administrator");
  for(String role:List.of("VIEWER","SIGNER"))mvc.perform(post("/api/v1/admin/users").header("X-Admin-Token",admin).contentType("application/json").content(json(Map.of("username",role.toLowerCase(),"password","testing-password-123","role",role)))).andExpect(status().isOk());
  String viewer=login("viewer"),signer=login("signer");
  mvc.perform(get("/api/v1/admin/users").header("X-Admin-Token",signer)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/portal/configuration").header("X-Admin-Token",viewer)).andExpect(status().isOk());
  mvc.perform(post("/api/v1/signing").header("X-Admin-Token",viewer)).andExpect(status().isForbidden());
  SigningJob foreign=new SigningJob();foreign.id=UUID.randomUUID().toString();foreign.owner="administrator";foreign.state="FAILED";foreign.title="Private job";foreign.format="image/png";foreign.createdAt=java.time.Instant.now();jobs.saveAndFlush(foreign);
  try {
   mvc.perform(get("/api/v1/jobs").header("X-Admin-Token",viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
   mvc.perform(get("/api/v1/jobs").header("X-Admin-Token",admin)).andExpect(jsonPath("$[0].id").value(foreign.id));
   mvc.perform(get("/api/v1/jobs/"+foreign.id+"/download").header("X-Admin-Token",signer)).andExpect(status().isNotFound());
   mvc.perform(post("/api/v1/jobs/"+foreign.id+"/retry").header("X-Admin-Token",signer)).andExpect(status().isNotFound());
   mvc.perform(post("/api/v1/jobs").header("X-Admin-Token",viewer)).andExpect(status().isForbidden());
   mvc.perform(post("/api/v1/jobs/"+foreign.id+"/retry").header("X-Admin-Token",viewer)).andExpect(status().isForbidden());
   mvc.perform(post("/api/v1/admin/signing-identity/private").header("X-Admin-Token",signer)).andExpect(status().isForbidden());
   mvc.perform(post("/api/v1/jobs/"+foreign.id+"/retry").header("X-Admin-Token",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("QUEUED"));
  } finally {jobs.deleteById(foreign.id);}
  var id=users.findByUsername("administrator").orElseThrow().id;
  mvc.perform(put("/api/v1/admin/users/"+id).header("X-Admin-Token",admin).contentType("application/json").content(json(Map.of("role","VIEWER","enabled",true)))).andExpect(status().isConflict());
  var signerId=users.findByUsername("signer").orElseThrow().id;
  mvc.perform(put("/api/v1/admin/users/"+signerId).header("X-Admin-Token",admin).contentType("application/json").content(json(Map.of("role","SIGNER","enabled",false)))).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",signer)).andExpect(status().isUnauthorized());
  mvc.perform(put("/api/v1/admin/users/"+signerId).header("X-Admin-Token",admin).contentType("application/json").content(json(Map.of("role","SIGNER","enabled",true)))).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",signer)).andExpect(status().isUnauthorized());
  String liveSigner=login("signer");
  mvc.perform(get("/api/v1/admin/sessions").header("X-Admin-Token",admin)).andExpect(status().isOk());
  mvc.perform(post("/api/v1/admin/users/"+signerId+"/revoke-sessions").header("X-Admin-Token",viewer)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/admin/users/"+signerId+"/revoke-sessions").header("X-Admin-Token",admin)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",liveSigner)).andExpect(status().isUnauthorized());
  String oldSigner=login("signer");String reset=json(Map.of("temporaryPassword","temporary-password-123","acknowledgeSessionRevocation",true));
  mvc.perform(post("/api/v1/admin/users/"+id+"/password-reset").header("X-Admin-Token",admin).contentType("application/json").content(reset)).andExpect(status().isBadRequest());
  mvc.perform(post("/api/v1/admin/users/"+signerId+"/password-reset").header("X-Admin-Token",admin).contentType("application/json").content(reset)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",oldSigner)).andExpect(status().isUnauthorized());
  String temporary=login("signer","temporary-password-123");
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",temporary)).andExpect(jsonPath("$.passwordChangeRequired").value(true));
  mvc.perform(get("/api/v1/portal/configuration").header("X-Admin-Token",temporary)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/jobs").header("X-Admin-Token",temporary)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/auth/password").header("X-Admin-Token",temporary).contentType("application/json").content(json(Map.of("currentPassword","temporary-password-123","newPassword","temporary-password-123")))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/v1/auth/password").header("X-Admin-Token",temporary).contentType("application/json").content(json(Map.of("currentPassword","temporary-password-123","newPassword","recovered-password-123")))).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",temporary)).andExpect(status().isUnauthorized());
  String recovered=login("signer","recovered-password-123");
  mvc.perform(get("/api/v1/portal/configuration").header("X-Admin-Token",recovered)).andExpect(status().isOk());
  mvc.perform(post("/api/v1/auth/logout").header("X-Admin-Token",viewer)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",viewer)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/admin/audit-events").header("X-Admin-Token",admin)).andExpect(jsonPath("$[0].actor").value("viewer"));
  String first=login("viewer"),second=login("viewer");
  mvc.perform(post("/api/v1/auth/password").header("X-Admin-Token",first).contentType("application/json").content(json(Map.of("currentPassword","testing-password-123","newPassword","changed-password-123")))).andExpect(status().isOk());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",first)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/auth/me").header("X-Admin-Token",second)).andExpect(status().isUnauthorized());
  var relogin=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json(Map.of("username","viewer","password","changed-password-123")))).andExpect(status().isOk()).andReturn();
  assertTrue(mapper.readTree(relogin.getResponse().getContentAsString()).has("token"));
  for(int i=0;i<5;i++)mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json(Map.of("username","administrator","password","wrong-password")))).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json(Map.of("username","administrator","password","testing-password-123")))).andExpect(status().isUnauthorized());
 }
}
