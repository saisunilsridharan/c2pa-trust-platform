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
 private final TrustPolicy trust;private final HardwareSigning hardware;
 public WorkerExecution(HardwareSigning hardware,TrustPolicy trust){this.trust=trust;this.hardware=hardware;}
 public int sign(Long workspace,Path worker,Path input,Path output,Path manifest,Path certificate,String key,Path report,Path error,int timeout)throws Exception {
  return sign(workspace,worker,input,output,manifest,certificate,key,report,error,timeout,null);
 }
 public int sign(Long workspace,Path worker,Path input,Path output,Path manifest,Path certificate,String key,Path report,Path error,int timeout,String trustSnapshot)throws Exception {
  Process process=null;Path bridge=null;ServerSocketChannel server=null;AtomicReference<SocketChannel> peer=new AtomicReference<>();Thread handler=null;
  try{
   List<String> command=new ArrayList<>(List.of(worker.toString(),"sign",input.toString(),output.toString(),manifest.toString(),certificate.toString(),key));
   if(key.startsWith("pkcs11:")){
    String identity=key.substring(7);hardware.get(identity,workspace);bridge=Files.createTempDirectory("c2pa-hsm-");Files.setPosixFilePermissions(bridge,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    Path socket=bridge.resolve("sign.sock");server=ServerSocketChannel.open(StandardProtocolFamily.UNIX);server.bind(UnixDomainSocketAddress.of(socket));
    var receiver=server;handler=Thread.ofVirtual().start(()->{
     try{for(int i=0;i<4;i++){try(var connection=receiver.accept()){peer.set(connection);var in=new DataInputStream(Channels.newInputStream(connection));int size=in.readInt();if(size<1 || size>65536)throw new IOException();byte[] data=in.readNBytes(size);if(data.length!=size)throw new IOException();byte[] signature=hardware.sign(identity,workspace,data);var out=new DataOutputStream(Channels.newOutputStream(connection));out.writeInt(signature.length);out.write(signature);out.flush();}finally{peer.set(null);}}}catch(Exception ignored){}
    });
    command.set(1,"sign-pkcs11");command.set(6,socket.toString());
   }
   if(trustSnapshot!=null){Path policy=report.getParent().resolve("trust-policy.json");Files.writeString(policy,trust.workerConfiguration(trustSnapshot));command.add(policy.toString());}
   process=new ProcessBuilder(command).redirectOutput(report.toFile()).redirectError(error.toFile()).start();
   if(!process.waitFor(timeout,TimeUnit.SECONDS))throw new IOException("Signing timed out");return process.exitValue();
  }finally{
   if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
   if(server!=null)server.close();var connection=peer.get();if(connection!=null)connection.close();if(handler!=null)handler.join(20000);
   if(bridge!=null){try(var files=Files.list(bridge)){for(var path:files.toList())Files.deleteIfExists(path);}Files.deleteIfExists(bridge);}
  }
 }
}
