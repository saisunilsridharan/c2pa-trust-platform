package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
@Service
public class WebhookService {
 public record Configuration(boolean enabled,String endpoint,String credentialId,boolean allowLoopbackHttp,String trustedCaPem,int maxAttempts){}
 private final CredentialService credentials;private final ObjectMapper mapper;private final WebhookSettingsRepository settings;private final WebhookVersionRepository versions;private final WebhookDeliveryRepository deliveries;
 public WebhookService(CredentialService credentials,ObjectMapper mapper,WebhookSettingsRepository settings,WebhookVersionRepository versions,WebhookDeliveryRepository deliveries){this.credentials=credentials;this.mapper=mapper;this.settings=settings;this.versions=versions;this.deliveries=deliveries;}
 public URI endpoint(Configuration configuration)throws Exception {
  try{URI uri=new URI(configuration.endpoint());if(uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getHost()==null || configuration.endpoint().length()>2000)throw new IllegalArgumentException();ServiceEndpoint.validate(new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toString(),configuration.allowLoopbackHttp());return uri;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use an HTTPS webhook endpoint without URL credentials or query parameters");}
 }
 public Configuration validate(Long workspace,Configuration configuration)throws Exception {
  if(configuration==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  if(!configuration.enabled())return new Configuration(false,null,null,false,null,1);
  if(configuration.maxAttempts()<1 || configuration.maxAttempts()>8 || (configuration.trustedCaPem()!=null && configuration.trustedCaPem().length()>12000))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid retry limit or CA bundle");
  endpoint(configuration);byte[] secret=credentials.read(workspace,Objects.requireNonNullElse(configuration.credentialId(),""));try{if(secret.length<32)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Webhook credential requires at least 32 bytes");}finally{Arrays.fill(secret,(byte)0);}PrivateObjectStorage.trust(configuration.trustedCaPem());return configuration;
 }
 public Configuration decode(String encoded)throws Exception{return mapper.readValue(encoded,Configuration.class);}
 public int send(Long workspace,String deliveryId,Configuration configuration,String payload)throws Exception {
  URI endpoint=endpoint(configuration);byte[] secret=credentials.read(workspace,configuration.credentialId());String timestamp=String.valueOf(Instant.now().getEpochSecond()),signature;
  try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"));signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"."+payload).getBytes(StandardCharsets.UTF_8)));}finally{Arrays.fill(secret,(byte)0);}
  var builder=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5));var managers=PrivateObjectStorage.trust(configuration.trustedCaPem());if(managers!=null){var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(null,managers,null);builder.sslContext(ssl);}
  try(var client=builder.build()) {var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("X-C2PA-Delivery-Id",deliveryId).header("X-C2PA-Timestamp",timestamp).header("X-C2PA-Signature","sha256="+signature).POST(HttpRequest.BodyPublishers.ofString(payload)).build();var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());try(var body=response.body()){return response.statusCode();}}
 }
 public void test(Long workspace,Configuration configuration)throws Exception {
  var c=validate(workspace,configuration);if(!c.enabled())return;
  try{int code=send(workspace,UUID.randomUUID().toString(),c,mapper.writeValueAsString(Map.of("type","connection.test","workspaceId",workspace)));if(code<200 || code>=300)throw new IllegalStateException();}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Webhook test failed; check TLS, endpoint and receiver response");}
 }
 /** Called in the job completion transaction: delivery is durable before dispatch. */
 public void enqueue(SigningJob job)throws Exception {
  var selected=settings.findById(job.workspaceId);if(selected.isEmpty() || selected.get().activeVersion==null)return;
  var version=versions.findById(selected.get().activeVersion).filter(v->v.workspaceId.equals(job.workspaceId)).orElseThrow();if(!decode(version.configuration).enabled())return;
  WebhookDelivery delivery=new WebhookDelivery();delivery.id=UUID.randomUUID().toString();delivery.eventKey=job.id+":"+job.attempts;delivery.workspaceId=job.workspaceId;delivery.configuration=version.configuration;delivery.createdAt=Instant.now();delivery.nextAttemptAt=delivery.createdAt;delivery.state="PENDING";delivery.payload=mapper.writeValueAsString(Map.of("type","signing."+job.state.toLowerCase(Locale.ROOT),"deliveryId",delivery.id,"jobId",job.id,"attempt",job.attempts,"workspaceId",job.workspaceId,"createdAt",delivery.createdAt.toString()));deliveries.saveAndFlush(delivery);
 }
}
