package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:second-factors","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-","portal.jobs.recovery.schedule=-","portal.webhooks.schedule=-"})
@AutoConfigureMockMvc
class SecondFactorTest {
 @Autowired AccountService accounts;@Autowired AccountFactorRepository factors;@Autowired UserRepository users;@Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
 @MockitoBean CredentialService credentials;
 @Test void rfc6238Vectors()throws Exception {byte[] secret="12345678901234567890".getBytes(StandardCharsets.US_ASCII);assertEquals("287082",Totp.code(secret,59/30));assertEquals("081804",Totp.code(secret,1111111109L/30));assertEquals("050471",Totp.code(secret,1111111111L/30));assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",Totp.encode(secret));}
 @Test void encryptedEnrollmentReplayRecoveryAndRevocation()throws Exception {
  Map<String,String> secrets=new HashMap<>();when(credentials.createProtected(anyString(),anyString())).thenAnswer(i->{var c=new EncryptedCredential();c.id=UUID.randomUUID().toString();c.kind="ACCOUNT_FACTOR";c.workspaceId=0L;secrets.put(c.id,i.getArgument(1));return c;});when(credentials.readProtected(anyString())).thenAnswer(i->secrets.get(i.getArgument(0)).getBytes(StandardCharsets.UTF_8));
  String password=UUID.randomUUID()+"x";var user=accounts.create("mfa-fixture",password,"SIGNER");String session=accounts.login(user.username,password).token();
  String proof=mapper.writeValueAsString(Map.of("password",password));var enrollment=mvc.perform(post("/api/v1/auth/mfa/enroll").header("X-Admin-Token",session).contentType("application/json").content(proof)).andExpect(status().isOk()).andReturn();assertTrue(mapper.readTree(enrollment.getResponse().getContentAsString()).get("uri").asText().startsWith("otpauth://"));
  var factor=factors.findById(user.id).orElseThrow();byte[] secret=Base64.getDecoder().decode(secrets.get(factor.pendingCredential));String otp=Totp.code(secret,Instant.now().getEpochSecond()/30);
  mvc.perform(post("/api/v1/auth/mfa/confirm").header("X-Admin-Token",session).contentType("application/json").content(mapper.writeValueAsString(Map.of("password",password,"code","bad")))).andExpect(status().isBadRequest());assertEquals(1,users.findById(user.id).orElseThrow().failedLogins);
  var confirmed=mvc.perform(post("/api/v1/auth/mfa/confirm").header("X-Admin-Token",session).contentType("application/json").content(mapper.writeValueAsString(Map.of("password",password,"code",otp)))).andExpect(status().isOk()).andReturn();var codes=mapper.readTree(confirmed.getResponse().getContentAsString()).get("recoveryCodes");assertEquals(8,codes.size());assertTrue(accounts.authenticate(session).isEmpty());assertNull(accounts.login(user.username,password));assertNull(accounts.login(user.username,password,otp));
  String first=codes.get(0).asText();session=accounts.login(user.username,password,first).token();assertNull(accounts.login(user.username,password,first));assertFalse(factors.findById(user.id).orElseThrow().recoveryHashes.contains(first));
  mvc.perform(get("/api/v1/auth/mfa").header("X-Admin-Token",session)).andExpect(status().isOk()).andExpect(jsonPath("$.secret").doesNotExist()).andExpect(jsonPath("$.recoveryCodesRemaining").value(7));
  var created=mvc.perform(post("/api/v1/auth/recovery-key").header("X-Admin-Token",session).contentType("application/json").content(mapper.writeValueAsString(Map.of("password",password,"code",codes.get(1).asText())))).andExpect(status().isOk()).andReturn();String key=mapper.readTree(created.getResponse().getContentAsString()).get("recoveryKey").asText();assertNotEquals(key,factors.findById(user.id).orElseThrow().accountRecoveryHash);
  String newPassword=UUID.randomUUID()+"x";mvc.perform(post("/api/v1/auth/recovery").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",user.username,"recoveryKey",key,"newPassword",newPassword)))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/v1/auth/recovery").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",user.username,"recoveryKey",key,"newPassword",newPassword,"code",codes.get(2).asText())))).andExpect(status().isOk());assertTrue(accounts.authenticate(session).isEmpty());assertNull(factors.findById(user.id).orElseThrow().accountRecoveryHash);assertNull(accounts.login(user.username,password,codes.get(3).asText()));assertNotNull(accounts.login(user.username,newPassword,codes.get(3).asText()));
  mvc.perform(post("/api/v1/auth/recovery").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",user.username,"recoveryKey",key,"newPassword",password,"code",codes.get(4).asText())))).andExpect(status().isBadRequest());
 }
}
