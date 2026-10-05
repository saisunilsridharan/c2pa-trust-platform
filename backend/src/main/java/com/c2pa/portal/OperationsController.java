package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.util.*;
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
@RestController
public class OperationsController {
 private final JobRepository jobs;private final DevelopmentIdentity identity;
 public OperationsController(JobRepository jobs,DevelopmentIdentity identity){this.jobs=jobs;this.identity=identity;}
 @GetMapping("/api/v1/admin/operations")
 public Map<String,Object> get(){return Map.of("database","CONNECTED","queueMode","Database-leased queue; shared storage required","queued",jobs.countByWorkspaceIdAndState(WorkspaceContext.id(),"QUEUED"),"running",jobs.countByWorkspaceIdAndState(WorkspaceContext.id(),"RUNNING"),"completed",jobs.countByWorkspaceIdAndState(WorkspaceContext.id(),"COMPLETED"),"failed",jobs.countByWorkspaceIdAndState(WorkspaceContext.id(),"FAILED"),"workerBinaryInstalled",Files.isExecutable(Path.of("../c2pa-worker/target/debug/c2pa-worker")),"certificate",identity.status());}
}
