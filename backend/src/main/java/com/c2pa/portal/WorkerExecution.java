package com.c2pa.portal;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.channels.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
@Service public class WorkerExecution {
 private final WorkerSandbox sandbox;private final PrivateTimestamps timestamps;private final TrustPolicy trust;private final HardwareSigning hardware;
 public WorkerExecution(HardwareSigning hardware,TrustPolicy trust,PrivateTimestamps timestamps,WorkerSandbox sandbox){this.sandbox=sandbox;this.timestamps=timestamps;this.trust=trust;this.hardware=hardware;}
 public int sign(Long workspace,Path worker,Path input,Path output,Path manifest,Path certificate,String key,Path report,Path error,int timeout)throws Exception {
  return sign(workspace,worker,input,output,manifest,certificate,key,report,error,timeout,null);
 }
 public int sign(Long workspace,Path worker,Path input,Path output,Path manifest,Path certificate,String key,Path report,Path error,int timeout,String trustSnapshot)throws Exception {
  return sign(workspace,worker,input,output,manifest,certificate,key,report,error,timeout,trustSnapshot,null);
 }
 public int sign(Long workspace,Path worker,Path input,Path output,Path manifest,Path certificate,String key,Path report,Path error,int timeout,String trustSnapshot,String timestampSnapshot)throws Exception {
  return sign(workspace,worker,input,output,manifest,certificate,key,report,error,timeout,trustSnapshot,timestampSnapshot,sandbox.current(workspace));
 }
 public int sign(Long workspace,Path worker,Path input,Path output,Path manifest,Path certificate,String key,Path report,Path error,int timeout,String trustSnapshot,String timestampSnapshot,WorkerSandbox.Budget budget)throws Exception {
  Process process=null;Path bridge=null;ServerSocketChannel server=null;AtomicReference<SocketChannel> peer=new AtomicReference<>();Thread handler=null;
  try{
   bridge=Files.createTempDirectory("c2pa-worker-private-");Files.setPosixFilePermissions(bridge,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
   List<String> command=new ArrayList<>(List.of(worker.toString(),"sign",input.toString(),output.toString(),manifest.toString(),certificate.toString(),key));
   if(key.startsWith("pkcs11:") || timestampSnapshot!=null){
    String identity=key.startsWith("pkcs11:")?key.substring(7):null;if(identity!=null)hardware.get(identity,workspace);
    Path socket=bridge.resolve("sign.sock");server=ServerSocketChannel.open(StandardProtocolFamily.UNIX);server.bind(UnixDomainSocketAddress.of(socket));
    var receiver=server;handler=Thread.ofVirtual().start(()->{
     try{for(int i=0;i<4;i++){try(var connection=receiver.accept()){peer.set(connection);var in=new DataInputStream(Channels.newInputStream(connection));int operation=in.readUnsignedByte();int size=in.readInt();if(size<1 || size>65536)throw new IOException();byte[] data=in.readNBytes(size);if(data.length!=size)throw new IOException();byte[] signature;if(operation==1 && identity!=null)signature=hardware.sign(identity,workspace,data);else if(operation==2 && timestampSnapshot!=null)signature=timestamps.submit(workspace,timestampSnapshot,data);else throw new IOException();var out=new DataOutputStream(Channels.newOutputStream(connection));out.writeInt(signature.length);out.write(signature);out.flush();}finally{peer.set(null);}}}catch(Exception ignored){}
    });
    if(identity!=null){command.set(1,"sign-pkcs11");command.set(6,socket.toString());}
    if(timestampSnapshot!=null){Path policy=bridge.resolve("trust-policy.json");Files.writeString(policy,trust.workerConfiguration(trustSnapshot,timestampSnapshot));command.add(policy.toString());command.add(socket.toString());}
   }
   if(trustSnapshot!=null && timestampSnapshot==null){Path policy=bridge.resolve("trust-policy.json");Files.writeString(policy,trust.workerConfiguration(trustSnapshot));command.add(policy.toString());}
   process=sandbox.process(command,budget).redirectOutput(report.toFile()).redirectError(error.toFile()).start();
   if(!process.waitFor(timeout,TimeUnit.SECONDS))throw new IOException("Signing timed out");if(process.exitValue()==0 && Files.size(report)>8*1024*1024L){Files.deleteIfExists(output);throw new IOException("Report exceeds limit");}return process.exitValue();
  }finally{
   if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
   if(server!=null)server.close();var connection=peer.get();if(connection!=null)connection.close();if(handler!=null)handler.join(20000);
   if(bridge!=null){try(var files=Files.list(bridge)){for(var path:files.toList())Files.deleteIfExists(path);}Files.deleteIfExists(bridge);}
  }
 }
 public int inspect(Long workspace,Path worker,Path input,String configuration,Path report,Path error,int timeout)throws Exception{
  Process process=null;Path scratch=Files.createTempDirectory("c2pa-inspection-private-");
  try{
   var command=new ArrayList<String>();if(configuration==null)command.addAll(List.of(worker.toString(),input.toString()));else{Path policy=scratch.resolve("policy.json");Files.writeString(policy,configuration);command.addAll(List.of(worker.toString(),"inspect",input.toString(),policy.toString()));}
   process=sandbox.process(command,sandbox.current(workspace)).redirectOutput(report.toFile()).redirectError(error.toFile()).start();if(!process.waitFor(timeout,TimeUnit.SECONDS))throw new IOException("Inspection timed out");return process.exitValue();
  }finally{if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}try(var files=Files.list(scratch)){for(var file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(scratch);}
 }

}
