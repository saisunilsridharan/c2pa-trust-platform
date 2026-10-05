package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkerSandboxTest {
 @Test void secretEnvironmentAndPrivatePathsAreNotPassedToNamespaceWorker()throws Exception{
  var sandbox=new WorkerSandbox(mock(ProcessingRepository.class));var budget=new WorkerSandbox.Budget(512,20,"NAMESPACE");
  var command=sandbox.command(List.of("/workspace/worker","sign-pkcs11","/private/original.png","/attempt/signed.png","/private/manifest.json","/private/certificate.pem","/bridge/sign.sock","/bridge/policy.json","/bridge/sign.sock"),budget);
  assertTrue(command.contains("--unshare-net"));assertTrue(command.contains("--unshare-pid"));assertTrue(command.contains("--as=536870912"));
  int delimiter=command.lastIndexOf("--");var child=command.subList(delimiter+1,command.size());
  assertEquals(List.of("/worker","sign-pkcs11","/input.png","/output/signed.png","/manifest","/certificate","/ipc/sign.sock","/policy","/ipc/sign.sock"),child);
  assertEquals(java.util.Map.of("PATH","/usr/bin:/bin"),sandbox.process(List.of("/workspace/worker"),new WorkerSandbox.Budget(512,20,"LIMITED")).environment());
 }
}
