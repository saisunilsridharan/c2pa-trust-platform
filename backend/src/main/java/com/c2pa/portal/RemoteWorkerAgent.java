package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
@Service public class RemoteWorkerAgent {
 public record Configuration(String hubEndpoint,String tokenCredential,String tlsCaPem,boolean allowLoopbackHttp){}
 public record Status(String state,Instant updatedAt){}
 private final RemoteWorkerSettingsRepository settings;private final CredentialService credentials;private final WorkerExecution execution;private final WorkerSandbox sandbox;private final ObjectMapper mapper;
 private final Set<Long> running=ConcurrentHashMap.newKeySet();private final Map<Long,Status> statuses=new ConcurrentHashMap<>();
 private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("remote-signing-agent-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 public RemoteWorkerAgent(RemoteWorkerSettingsRepository settings,CredentialService credentials,WorkerExecution execution,WorkerSandbox sandbox,ObjectMapper mapper){this.settings=settings;this.credentials=credentials;this.execution=execution;this.sandbox=sandbox;this.mapper=mapper;}
 public Status status(Long workspace){return statuses.getOrDefault(workspace,new Status("IDLE",null));}
 public Configuration decode(String json)throws Exception{return mapper.readValue(json,Configuration.class);}
 public void validate(Long workspace,Configuration c)throws Exception{
  if(c==null || c.hubEndpoint()==null || c.hubEndpoint().length()>2000 || c.tlsCaPem()!=null && c.tlsCaPem().length()>12000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  ServiceEndpoint.validate(c.hubEndpoint(),c.allowLoopbackHttp());PrivateObjectStorage.trust(c.tlsCaPem());
  byte[] secret=credentials.read(workspace,c.tokenCredential());try{if(!new String(secret,java.nio.charset.StandardCharsets.US_ASCII).matches("WKR\\.[0-9a-f-]{36}\\.[0-9a-f]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a paired worker credential");}finally{Arrays.fill(secret,(byte)0);}
 }
 private void active(RemoteWorkerSettings selected){var current=settings.findById(selected.id).orElseThrow();if(!current.agentEnabled || !Objects.equals(current.revision,selected.revision) || !Objects.equals(current.agentConfiguration,selected.agentConfiguration))throw new IllegalStateException("Agent configuration changed");}
 private HttpResponse<byte[]> send(Long workspace,Configuration c,String path,String lease,Integer operation,HttpRequest.BodyPublisher body,int limit,int timeout)throws Exception{
  validate(workspace,c);var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);var managers=PrivateObjectStorage.trust(c.tlsCaPem());if(managers!=null){var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(null,managers,null);builder.sslContext(ssl);}
  var request=HttpRequest.newBuilder(URI.create(c.hubEndpoint().replaceAll("/$","")+"/api/v1/worker-protocol"+path)).timeout(Duration.ofSeconds(timeout));
  byte[] secret=credentials.read(workspace,c.tokenCredential());try{request.header("Authorization","Bearer "+new String(secret,java.nio.charset.StandardCharsets.US_ASCII));}finally{Arrays.fill(secret,(byte)0);}
  if(lease!=null)request.header("X-Worker-Lease",lease);if(operation!=null)request.header("X-Worker-Operation",operation.toString());if(body==null)request.GET();else request.header("Content-Type","application/octet-stream").POST(body);
  try(var client=builder.build()){var response=client.send(request.build(),info->new BoundedHttpBody(limit));if(response.statusCode()!=200 && response.statusCode()!=204)throw new IOException("Hub request failed ("+response.statusCode()+")");return response;}
 }
 public Map<String,Object> test(Long workspace,Configuration c)throws Exception{
  validate(workspace,c);var capabilities=new LinkedHashMap<>(sandbox.capabilities());capabilities.put("protocol",1);if(!Boolean.TRUE.equals(capabilities.get("resourceLimitsAvailable")) || !Files.isExecutable(Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath()))throw new IllegalStateException("Worker runtime unavailable");
  var response=send(workspace,c,"/heartbeat",null,null,HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(capabilities)),4096,15);
  var json=mapper.readTree(response.body());if(json.path("protocol").asInt()!=1)throw new IllegalStateException();return Map.of("workerId",json.path("workerId").asText(),"connected",true,"namespaceIsolationAvailable",capabilities.get("namespaceIsolationAvailable"));
 }
 @Scheduled(fixedDelay=5000) public void poll(){for(var config:settings.findByAgentEnabledTrue(PageRequest.of(0,100))){if(!running.add(config.id))continue;try{executor.execute(()->{try{WorkspaceContext.call(config.id,()->{run(config);return null;});}catch(Exception e){org.slf4j.LoggerFactory.getLogger(RemoteWorkerAgent.class).warn("Remote agent rejected: {} at line {}",e.getClass().getSimpleName(),e.getStackTrace()[0].getLineNumber());statuses.put(config.id,new Status("FAILED",Instant.now()));}finally{running.remove(config.id);}});}catch(RejectedExecutionException e){running.remove(config.id);}}}
 private void run(RemoteWorkerSettings selected)throws Exception{
  active(selected);var c=decode(selected.agentConfiguration);test(selected.id,c);active(selected);
  var response=send(selected.id,c,"/claim",null,null,HttpRequest.BodyPublishers.noBody(),1024*1024,30);
  if(response.statusCode()==204){statuses.put(selected.id,new Status("IDLE",Instant.now()));return;}
  var job=mapper.readValue(response.body(),RemoteWorkerProtocol.Claim.class);UUID.fromString(job.id());UUID.fromString(job.lease());
  if(!Set.of(".jpg",".png",".webp",".tif",".wav",".mp3",".flac",".mp4",".pdf").contains(job.extension()) || job.timeout()<10 || job.timeout()>120 || job.manifest().length()>100000 || job.certificate().length()>60000 || job.policy()!=null && job.policy().length()>200000)throw new IllegalStateException("Invalid remote job");
  statuses.put(selected.id,new Status("RUNNING",Instant.now()));Path folder=Files.createTempDirectory("c2pa-remote-job-");
  try{
   active(selected);byte[] source=send(selected.id,c,"/jobs/"+job.id()+"/source",job.lease(),null,null,100*1024*1024,60).body();
   if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)).equals(job.sourceSha256()))throw new IllegalStateException("Source hash changed");
   Path input=folder.resolve("original"+job.extension()),output=folder.resolve("signed"+job.extension()),manifest=folder.resolve("manifest.json"),certificate=folder.resolve("chain.pem");Files.write(input,source);Files.writeString(manifest,job.manifest());Files.writeString(certificate,job.certificate());source=null;
   int exit=execution.signRemote(selected.id,Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize(),input,output,manifest,certificate,folder.resolve("report.json"),folder.resolve("error.log"),job.timeout(),job.policy(),job.timestamp(),new WorkerSandbox.Budget(job.memoryMb(),job.cpuSeconds(),job.sandboxMode()),(operation,data)->{active(selected);return send(selected.id,c,"/jobs/"+job.id()+"/callback",job.lease(),operation,HttpRequest.BodyPublishers.ofByteArray(data),65536,60).body();});
   if(exit!=0 || !Files.isRegularFile(output) || Files.size(output)>128*1024*1024L)throw new IllegalStateException("Remote signing failed");
   active(selected);send(selected.id,c,"/jobs/"+job.id()+"/complete",job.lease(),null,HttpRequest.BodyPublishers.ofFile(output),4096,150);statuses.put(selected.id,new Status("IDLE",Instant.now()));
  }catch(Exception e){org.slf4j.LoggerFactory.getLogger(RemoteWorkerAgent.class).warn("Remote attempt rejected: {} at line {}{}",e.getClass().getSimpleName(),e.getStackTrace()[0].getLineNumber(),e instanceof IOException && e.getMessage()!=null && e.getMessage().startsWith("Hub request failed (")?"; "+e.getMessage():"");try{send(selected.id,c,"/jobs/"+job.id()+"/fail",job.lease(),null,HttpRequest.BodyPublishers.noBody(),4096,15);}catch(Exception ignored){}throw e;}
  finally{try(var files=Files.walk(folder)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
}
