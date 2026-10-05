package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PrivateObjectStorageTest {
 @TempDir Path folder;
 static byte[] hmac(byte[] key,String value)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
 static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 static boolean authorized(HttpExchange request,String access,String secret)throws Exception{
  String authorization=request.getRequestHeaders().getFirst("Authorization");if(authorization==null || !authorization.startsWith("AWS4-HMAC-SHA256 "))return false;
  Map<String,String> parts=new HashMap<>();for(String part:authorization.substring(17).split(", ")){var pair=part.split("=",2);parts.put(pair[0],pair[1]);}
  String[] scope=parts.get("Credential").split("/");if(scope.length!=5 || !scope[0].equals(access))return false;
  StringBuilder headers=new StringBuilder();for(String name:parts.get("SignedHeaders").split(";"))headers.append(name).append(':').append(request.getRequestHeaders().getFirst(name).trim().replaceAll("\\s+"," ")).append('\n');
  String canonical=request.getRequestMethod()+"\n"+request.getRequestURI().getRawPath()+"\n"+Objects.requireNonNullElse(request.getRequestURI().getRawQuery(),"")+"\n"+headers+"\n"+parts.get("SignedHeaders")+"\n"+request.getRequestHeaders().getFirst("x-amz-content-sha256");
  String scopeString=String.join("/",Arrays.copyOfRange(scope,1,5));String stringToSign="AWS4-HMAC-SHA256\n"+request.getRequestHeaders().getFirst("x-amz-date")+"\n"+scopeString+"\n"+hash(canonical.getBytes(StandardCharsets.UTF_8));
  byte[] key=("AWS4"+secret).getBytes(StandardCharsets.UTF_8);for(int i=1;i<5;i++)key=hmac(key,scope[i]);return MessageDigest.isEqual(HexFormat.of().formatHex(hmac(key,stringToSign)).getBytes(StandardCharsets.US_ASCII),parts.get("Signature").getBytes(StandardCharsets.US_ASCII));
 }
 static byte[] decoded(HttpExchange request,byte[] bytes)throws Exception {
  String encoding=request.getRequestHeaders().getFirst("Content-Encoding");if(encoding==null || !encoding.contains("aws-chunked"))return bytes;
  var out=new java.io.ByteArrayOutputStream();int at=0;while(at<bytes.length){int end=at;while(end+1<bytes.length && !(bytes[end]==13 && bytes[end+1]==10))end++;String size=new String(bytes,at,end-at,StandardCharsets.US_ASCII).split(";",2)[0];int n=Integer.parseInt(size,16);if(n==0)break;at=end+2;out.write(bytes,at,n);at+=n+2;}return out.toByteArray();
 }
 @Test void signedProtocolRoundTripSnapshotsIsolationAndFailure()throws Exception{
  String access="fixture-"+UUID.randomUUID(),secret=UUID.randomUUID().toString();Map<String,byte[]> objects=new ConcurrentHashMap<>();List<String> failures=new CopyOnWriteArrayList<>();
  var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",request->{try{
   if(!authorized(request,access,secret)){request.sendResponseHeaders(403,-1);return;}
   String path=request.getRequestURI().getPath();byte[] body=decoded(request,request.getRequestBody().readAllBytes());
   switch(request.getRequestMethod()){
    case "PUT" -> {objects.put(path,body);request.sendResponseHeaders(200,-1);}
    case "GET" -> {byte[] value=objects.get(path);if(value==null){request.sendResponseHeaders(404,-1);break;}request.sendResponseHeaders(200,value.length);request.getResponseBody().write(value);}
    case "DELETE" -> {objects.remove(path);request.sendResponseHeaders(204,-1);}
    default -> request.sendResponseHeaders(405,-1);
   }
  }catch(Exception e){failures.add(e.getClass().getSimpleName()+":"+e.getMessage());request.sendResponseHeaders(500,-1);}finally{request.close();}});server.start();
  try{
   var credentials=mock(CredentialService.class);when(credentials.read(1L,"access")).thenAnswer(i->access.getBytes(StandardCharsets.UTF_8));when(credentials.read(1L,"secret")).thenAnswer(i->secret.getBytes(StandardCharsets.UTF_8));
   var mapper=new ObjectMapper();var storage=new PrivateObjectStorage(credentials,mapper);var configuration=new PrivateObjectStorage.Configuration("S3","http://127.0.0.1:"+server.getAddress().getPort(),"us-east-1","portal-assets","portal","access","secret",true,null);
   storage.test(1L,configuration);assertTrue(objects.isEmpty(),"Test objects must be deleted");
   var job=new SigningJob();job.id=UUID.randomUUID().toString();job.format="image/png";job.storageSnapshot=mapper.writeValueAsString(configuration);Path file=folder.resolve("asset");byte[] content={1,2,3,4};Files.write(file,content);
   storage.write(job,"original.png",file);assertArrayEquals(content,storage.read(job,"original.png",100));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->storage.read(job,"original.png",2));assertTrue(objects.keySet().stream().allMatch(k->k.startsWith("/portal-assets/portal/workspace-1/jobs/")));
   var other=new SigningJob();other.id=job.id;other.format=job.format;other.storageSnapshot=job.storageSnapshot;other.workspaceId=2L;when(credentials.read(2L,"access")).thenAnswer(i->access.getBytes(StandardCharsets.UTF_8));when(credentials.read(2L,"secret")).thenAnswer(i->secret.getBytes(StandardCharsets.UTF_8));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->storage.read(other,"original.png",100));
   storage.delete(job);assertTrue(objects.isEmpty());when(credentials.read(1L,"secret")).thenAnswer(i->"wrong-key".getBytes(StandardCharsets.UTF_8));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->storage.test(1L,configuration));assertTrue(failures.isEmpty(),failures.toString());
  }finally{server.stop(0);}
 }
 @Test void endpointPolicyRejectsMetadataAndUnsafeHttp()throws Exception {
  assertThrows(Exception.class,()->ServiceEndpoint.validate("http://169.254.169.254",true));assertThrows(Exception.class,()->ServiceEndpoint.validate("https://100.100.100.200",false));assertThrows(Exception.class,()->ServiceEndpoint.validate("http://192.168.1.4",true));assertThrows(Exception.class,()->ServiceEndpoint.validate("http://127.0.0.1",false));assertThrows(Exception.class,()->ServiceEndpoint.validate("https://user:password@127.0.0.1",false));assertThrows(Exception.class,()->ServiceEndpoint.validate("https://127.0.0.1/path",false));assertThrows(Exception.class,()->ServiceEndpoint.validate("https://[::]",false));assertNotNull(ServiceEndpoint.validate("http://127.0.0.1:9000",true));
 }
}
