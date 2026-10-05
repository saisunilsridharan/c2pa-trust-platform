package com.c2pa.portal;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
@Service public class WorkerSandbox {
 public record Budget(int memoryMb,int cpuSeconds,String mode){}
 private final ProcessingRepository settings;
 public WorkerSandbox(ProcessingRepository settings){this.settings=settings;}
 public Budget current(Long workspace){return settings.findById(workspace).map(s->new Budget(s.maxMemoryMb,s.maxCpuSeconds,s.sandboxMode)).orElse(new Budget(1024,45,"LIMITED"));}
 public List<String> command(List<String> original,Budget budget)throws Exception{
  if(budget.memoryMb()<256 || budget.memoryMb()>4096 || budget.cpuSeconds()<1 || budget.cpuSeconds()>120 || !Set.of("LIMITED","NAMESPACE").contains(budget.mode()))throw new IllegalArgumentException();
  if(!Files.isExecutable(Path.of("/usr/bin/prlimit")))throw new IllegalStateException("Linux resource-limit support is required");
  var result=new ArrayList<>(List.of("/usr/bin/prlimit","--as="+(budget.memoryMb()*1024L*1024),"--cpu="+budget.cpuSeconds(),"--fsize="+(128*1024*1024),"--nofile=128","--"));
  if(budget.mode().equals("LIMITED")){result.addAll(original);return result;}
  if(!Files.isExecutable(Path.of("/usr/bin/bwrap")))throw new IllegalStateException("Bubblewrap is required");
  var mount=new ArrayList<>(List.of("/usr/bin/bwrap","--unshare-user","--unshare-pid","--unshare-net","--unshare-ipc","--unshare-uts","--new-session","--die-with-parent","--cap-drop","ALL"));
  for(String directory:List.of("/usr","/lib","/lib64"))if(Files.exists(Path.of(directory)))mount.addAll(List.of("--ro-bind",directory,directory));
  mount.addAll(List.of("--proc","/proc","--dev","/dev","--size",String.valueOf(128*1024*1024),"--tmpfs","/tmp","--clearenv","--setenv","PATH","/usr/bin:/bin","--setenv","TMPDIR","/tmp"));
  var args=new ArrayList<>(original);bind(mount,original.getFirst(),"/worker",true);args.set(0,"/worker");
  if(args.size()>1 && args.get(1).startsWith("sign")){
   String inputPath=inputPath(args.get(2));bind(mount,args.get(2),inputPath,true);args.set(2,inputPath);Path output=Path.of(args.get(3)).toAbsolutePath().normalize();bind(mount,output.getParent().toString(),"/output",false);args.set(3,"/output/"+output.getFileName());
   bind(mount,args.get(4),"/manifest",true);args.set(4,"/manifest");bind(mount,args.get(5),"/certificate",true);args.set(5,"/certificate");
   String socket=args.get(1).equals("sign-pkcs11")?args.get(6):args.size()>8?args.get(8):null;
   if(socket!=null){Path directory=Path.of(socket).toAbsolutePath().getParent();bind(mount,directory.toString(),"/ipc",true);if(args.get(1).equals("sign-pkcs11"))args.set(6,"/ipc/"+Path.of(socket).getFileName());if(args.size()>8)args.set(8,"/ipc/"+Path.of(socket).getFileName());}
   if(!args.get(1).equals("sign-pkcs11")){bind(mount,args.get(6),"/key",true);args.set(6,"/key");}
   if(args.size()>7){bind(mount,args.get(7),"/policy",true);args.set(7,"/policy");}
  }else if(args.size()>1){
   int input=args.get(1).equals("inspect")?2:1;String inputPath=inputPath(args.get(input));bind(mount,args.get(input),inputPath,true);args.set(input,inputPath);if(args.size()>3){bind(mount,args.get(3),"/policy",true);args.set(3,"/policy");}
  }
  mount.addAll(List.of("--chdir","/","--"));mount.addAll(args);result.addAll(mount);return result;
 }
 private String inputPath(String input){String name=Path.of(input).getFileName().toString();int dot=name.lastIndexOf('.');return "/input"+(dot<0?"":name.substring(dot));}
 private void bind(List<String> command,String source,String target,boolean readOnly){command.addAll(List.of(readOnly?"--ro-bind":"--bind",Path.of(source).toAbsolutePath().normalize().toString(),target));}
 public ProcessBuilder process(List<String> original,Budget budget)throws Exception{
  var process=new ProcessBuilder(command(original,budget));process.environment().clear();process.environment().put("PATH","/usr/bin:/bin");return process;
 }
 public boolean namespaceAvailable(){Process process=null;try{process=process(List.of("/usr/bin/true"),new Budget(256,2,"NAMESPACE")).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();return process.waitFor(5,TimeUnit.SECONDS) && process.exitValue()==0;}catch(Exception e){return false;}finally{if(process!=null && process.isAlive())process.destroyForcibly();}}
 public Map<String,Object> capabilities(){boolean limits=Files.isExecutable(Path.of("/usr/bin/prlimit")),namespaces=namespaceAvailable();return Map.of("resourceLimitsAvailable",limits,"namespaceIsolationAvailable",namespaces,"message",namespaces?"Namespace and resource-limit probe passed on this worker host.":"Namespace isolation is unavailable here. The deployment runtime must allow user/PID/network namespaces and provide bubblewrap; Limited mode applies resource limits only.");}
}
