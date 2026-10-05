package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:api-keys","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-"})
@AutoConfigureMockMvc
class ApiKeysTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired AccountService accounts;@Autowired ApiKeyRepository keys;@Autowired MembershipRepository memberships;
 @Test void hashedScopedRevocableAndMembershipAwareKeys()throws Exception{
  String password=UUID.randomUUID()+"x";var signer=accounts.create("key-signer",password,"SIGNER");String session=accounts.login(signer.username,password).token();
  var created=mapper.readTree(mvc.perform(post("/api/v1/auth/api-keys").header("X-Admin-Token",session).contentType("application/json").content("{\"label\":\"Read integration\",\"scopes\":[\"READ\"],\"days\":30}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  String token=created.get("token").asText(),id=created.get("key").get("id").asText();assertNotEquals(token,keys.findById(id).orElseThrow().tokenHash);assertEquals(AccountService.hash(token),keys.findById(id).orElseThrow().tokenHash);
  mvc.perform(get("/api/v1/portal/capabilities").header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/jobs").header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/admin/configuration").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/jobs").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/auth/api-keys").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/jobs").header("Authorization","Bearer "+token).header("X-Workspace-Id",2)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/auth/api-keys").header("X-Admin-Token",session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].token").doesNotExist()).andExpect(jsonPath("$[0].tokenHash").doesNotExist());
  mvc.perform(get("/api/v1/portal/revocation").header("X-Admin-Token",session)).andExpect(status().isOk()).andExpect(jsonPath("$.issuerCertificatesPem").doesNotExist());
  mvc.perform(get("/api/v1/portal/revocation").header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/admin/revocation").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/admin/revocation/draft").header("Authorization","Bearer "+token).contentType("application/json").content("{\"revision\":0,\"configuration\":{\"enabled\":false}}")).andExpect(status().isForbidden());
  var member=memberships.findByUserIdAndWorkspaceId(signer.id,1L).orElseThrow();memberships.delete(member);
  mvc.perform(get("/api/v1/jobs").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  member.id=null;member.revision=null;memberships.saveAndFlush(member);
  mvc.perform(delete("/api/v1/auth/api-keys/"+id).header("X-Admin-Token",session)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/jobs").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
  var second=mapper.readTree(mvc.perform(post("/api/v1/auth/api-keys").header("X-Admin-Token",session).contentType("application/json").content("{\"label\":\"Reset test\",\"scopes\":[\"SIGN\"],\"days\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  accounts.resetPassword(signer.id,UUID.randomUUID()+"x");mvc.perform(get("/api/v1/jobs").header("Authorization","Bearer "+second.get("token").asText())).andExpect(status().isUnauthorized());
  var viewer=accounts.create("key-viewer",password,"VIEWER");String viewerSession=accounts.login(viewer.username,password).token();
  mvc.perform(post("/api/v1/auth/api-keys").header("X-Admin-Token",viewerSession).contentType("application/json").content("{\"label\":\"Forbidden\",\"scopes\":[\"SIGN\"],\"days\":1}")).andExpect(status().isBadRequest());
 }
}
