package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/private-ca/renewals")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class CertificateRenewalController {
 public record Create(String providerVersion,String choiceId,Long choiceRevision,HardwareCertificateRequests.Subject subject,int renewBeforeHours,boolean acknowledgeAutomaticApproval){}
 public record Enable(Long revision,boolean enabled,Long choiceRevision,boolean acknowledgeAutomaticApproval){}
 public record Run(Long revision,boolean acknowledgeAutomaticApproval){}
 public record View(String id,Long revision,String providerVersion,String currentChoiceId,Long currentChoiceRevision,String label,String fingerprint,HardwareCertificateRequests.Subject subject,int renewBeforeHours,boolean enabled,String state,int attempts,Instant nextCheckAt,Instant createdAt,Instant lastRenewedAt,String lastIdentityId,String error){}
 private final CertificateRenewalPlanRepository plans;private final CertificateRenewals renewals;private final CertificateRenewalScheduler scheduler;private final SigningOptionRepository options;private final ObjectMapper mapper;
 public CertificateRenewalController(CertificateRenewalPlanRepository plans,CertificateRenewals renewals,CertificateRenewalScheduler scheduler,SigningOptionRepository options,ObjectMapper mapper){this.plans=plans;this.renewals=renewals;this.scheduler=scheduler;this.options=options;this.mapper=mapper;}
 private void acknowledge(boolean accepted){if(!accepted)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Authorize automatic issuer requests, publication of tested replacement choices and withdrawal of their predecessors");}
 private View view(CertificateRenewalPlan p)throws Exception{var o=options.findById(p.currentChoiceId).filter(v->v.workspaceId.equals(p.workspaceId)).orElse(null);return new View(p.id,p.revision,p.providerVersion,p.currentChoiceId,p.currentChoiceRevision,o==null?null:o.label,o==null?null:o.fingerprint,mapper.readValue(p.subjectConfiguration,HardwareCertificateRequests.Subject.class),p.renewBeforeHours,p.enabled,p.state,p.attempts,p.nextCheckAt,p.createdAt,p.lastRenewedAt,p.lastIdentityId,p.error);}
 @GetMapping public List<View> list(@RequestParam(defaultValue="0") int page)throws Exception{if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var result=new ArrayList<View>();for(var p:plans.findByWorkspaceIdOrderByCreatedAtDesc(WorkspaceContext.id(),org.springframework.data.domain.PageRequest.of(page,50)))result.add(view(p));return result;}
 @PostMapping public View create(@RequestBody Create request)throws Exception{acknowledge(request.acknowledgeAutomaticApproval());return view(renewals.create(WorkspaceContext.id(),request.providerVersion(),request.choiceId(),request.choiceRevision(),request.subject(),request.renewBeforeHours()));}
 @PutMapping("/{id}") public View enabled(@PathVariable String id,@RequestBody Enable request)throws Exception{if(request.enabled())acknowledge(request.acknowledgeAutomaticApproval());return view(renewals.enabled(WorkspaceContext.id(),id,request.revision(),request.enabled(),request.choiceRevision()));}
 @PostMapping("/{id}/run") public View run(@PathVariable String id,@RequestBody Run request)throws Exception{acknowledge(request.acknowledgeAutomaticApproval());var p=renewals.get(id,WorkspaceContext.id());if(!Objects.equals(p.revision,request.revision()) || !p.enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Refresh the enabled renewal plan");if(!scheduler.dispatch(id,true,request.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Renewal plan is busy or blocked");return view(renewals.get(id,WorkspaceContext.id()));}
}
