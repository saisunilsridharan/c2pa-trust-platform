package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:webhooks","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-","portal.webhooks.schedule=-"})
@AutoConfigureMockMvc
class WebhooksTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JobRepository jobs;@Autowired JobCompletion completion;@Autowired WebhookDeliveryRepository deliveries;@Autowired WebhookDispatcher dispatcher;
 @MockitoBean CredentialService credentials;
 @Test void signedOutboxRetriesSnapshotsAndNoRedirects()throws Exception {
  String secret=UUID.randomUUID().toString();when(credentials.read(1L,"hmac-secret")).thenAnswer(i->secret.getBytes(StandardCharsets.UTF_8));AtomicInteger code=new AtomicInteger(302),requests=new AtomicInteger(),badSignatures=new AtomicInteger();
  var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/events",request->{try{requests.incrementAndGet();String time=request.getRequestHeaders().getFirst("X-C2PA-Timestamp");String payload=new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);String signature=request.getRequestHeaders().getFirst("X-C2PA-Signature");String expected="sha256="+HexFormat.of().formatHex(PrivateObjectStorageTest.hmac(secret.getBytes(StandardCharsets.UTF_8),time+"."+payload));if(!expected.equals(signature) || Math.abs(Instant.now().getEpochSecond()-Long.parseLong(time))>60 || request.getRequestHeaders().getFirst("X-C2PA-Delivery-Id")==null){badSignatures.incrementAndGet();request.sendResponseHeaders(403,-1);}else{request.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/unexpected");request.sendResponseHeaders(code.get(),-1);}}catch(Exception e){request.sendResponseHeaders(500,-1);}finally{request.close();}});server.start();
  try{
   String token=Files.readString(Path.of(".local/admin-token")).trim(),base="/api/v1/admin/webhooks";
   var initial=mapper.readTree(mvc.perform(get(base).header("X-Admin-Token",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   var configuration=Map.of("enabled",true,"endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/events","credentialId","hmac-secret","allowLoopbackHttp",true,"maxAttempts",2);
   String draft=mapper.writeValueAsString(Map.of("revision",initial.get("revision").asLong(),"configuration",configuration));
   var saved=mapper.readTree(mvc.perform(put(base+"/draft").header("X-Admin-Token",token).contentType("application/json").content(draft)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   mvc.perform(put(base+"/draft").header("X-Admin-Token",token).contentType("application/json").content(draft)).andExpect(status().isConflict());
   String selection=mapper.writeValueAsString(Map.of("revision",saved.get("revision").asLong(),"versionId",saved.get("draft").get("id").asText(),"acknowledgeActivation",true));
   mvc.perform(post(base+"/activate").header("X-Admin-Token",token).contentType("application/json").content(selection)).andExpect(status().isConflict());
   mvc.perform(post(base+"/test").header("X-Admin-Token",token).contentType("application/json").content(selection)).andExpect(status().isBadGateway());assertEquals(1,requests.get(),"Redirects must not be followed");
   code.set(200);mvc.perform(post(base+"/test").header("X-Admin-Token",token).contentType("application/json").content(selection)).andExpect(status().isOk());
   var active=mapper.readTree(mvc.perform(post(base+"/activate").header("X-Admin-Token",token).contentType("application/json").content(selection)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   SigningJob job=new SigningJob();job.id=UUID.randomUUID().toString();job.state="RUNNING";job.owner="fixture-owner";job.attempts=1;job=jobs.saveAndFlush(job);job.state="COMPLETED";completion.finish(job);assertEquals(1,deliveries.count());var delivery=deliveries.findAll().getFirst();assertFalse(delivery.payload.contains(secret));
   var disabled=mapper.readTree(mvc.perform(put(base+"/draft").header("X-Admin-Token",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",active.get("revision").asLong(),"configuration",Map.of("enabled",false))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   String disableSelection=mapper.writeValueAsString(Map.of("revision",disabled.get("revision").asLong(),"versionId",disabled.get("draft").get("id").asText(),"acknowledgeActivation",true));mvc.perform(post(base+"/test").header("X-Admin-Token",token).contentType("application/json").content(disableSelection)).andExpect(status().isOk());mvc.perform(post(base+"/activate").header("X-Admin-Token",token).contentType("application/json").content(disableSelection)).andExpect(status().isOk());
   code.set(503);dispatcher.dispatch(delivery.id);delivery=deliveries.findById(delivery.id).orElseThrow();assertEquals("PENDING",delivery.state);assertEquals(1,delivery.attempts);assertTrue(delivery.nextAttemptAt.isAfter(Instant.now()));delivery.nextAttemptAt=Instant.now().minusSeconds(1);deliveries.saveAndFlush(delivery);dispatcher.dispatch(delivery.id);delivery=deliveries.findById(delivery.id).orElseThrow();assertEquals("FAILED",delivery.state);assertEquals(2,delivery.attempts);
   mvc.perform(post(base+"/deliveries/"+delivery.id+"/retry").header("X-Admin-Token",token)).andExpect(status().isOk());code.set(200);dispatcher.dispatch(delivery.id);delivery=deliveries.findById(delivery.id).orElseThrow();assertEquals("DELIVERED",delivery.state);assertEquals(1,delivery.attempts);assertEquals(0,badSignatures.get());
   mvc.perform(post(base+"/deliveries/"+delivery.id+"/retry").header("X-Admin-Token",token)).andExpect(status().isConflict());
   mvc.perform(get(base+"/deliveries").header("X-Admin-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].payload").doesNotExist()).andExpect(jsonPath("$[0].configuration").doesNotExist());
  }finally{server.stop(0);}
 }
}
