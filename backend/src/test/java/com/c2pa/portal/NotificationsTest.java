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
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:notifications","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-"})
@AutoConfigureMockMvc
class NotificationsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired AccountService accounts;@Autowired JobRepository jobs;@Autowired JobCompletion completion;@Autowired NotificationRepository notifications;
 @Test void durableAttemptOutcomesIdempotencyAndOwnership()throws Exception {
  String password=UUID.randomUUID()+"x";var owner=accounts.create("notification-owner",password,"SIGNER");var other=accounts.create("notification-other",password,"SIGNER");String session=accounts.login(owner.username,password).token(),foreign=accounts.login(other.username,password).token();
  SigningJob job=new SigningJob();job.id=UUID.randomUUID().toString();job.owner=owner.username;job.state="RUNNING";job.createdAt=java.time.Instant.now();job.attempts=1;job=jobs.saveAndFlush(job);job.state="FAILED";job.completedAt=java.time.Instant.now();completion.finish(job);job=jobs.findById(job.id).orElseThrow();completion.finish(job);
  assertEquals(1,notifications.count());assertEquals("FAILED",jobs.findById(job.id).orElseThrow().state);
  var result=mvc.perform(get("/api/v1/notifications").header("X-Admin-Token",session)).andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1)).andExpect(jsonPath("$.items[0].outcome").value("FAILED")).andReturn();String id=mapper.readTree(result.getResponse().getContentAsString()).get("items").get(0).get("id").asText();
  mvc.perform(get("/api/v1/notifications").header("X-Admin-Token",foreign)).andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(0));
  mvc.perform(post("/api/v1/notifications/"+id+"/read").header("X-Admin-Token",foreign)).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/notifications/"+id+"/read").header("X-Admin-Token",session)).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
  var readAt=notifications.findById(id).orElseThrow().readAt;
  mvc.perform(post("/api/v1/notifications/"+id+"/read").header("X-Admin-Token",session)).andExpect(status().isOk());assertEquals(readAt,notifications.findById(id).orElseThrow().readAt);
  job=jobs.findById(job.id).orElseThrow();job.attempts=2;job.state="COMPLETED";completion.finish(job);assertEquals(2,notifications.count());
  mvc.perform(get("/api/v1/notifications").header("X-Admin-Token",session)).andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1));
 }
}
