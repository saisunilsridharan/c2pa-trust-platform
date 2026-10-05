package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Objects;
@RestController @RequestMapping("/api/v1/admin/authentication/rate-limits")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class AuthenticationRateController {
 public record Policy(Long revision,int windowSeconds,int perAddressLimit,int globalLimit,String trustedProxyCidrs){}
 public record Update(@NotNull Long revision,@Min(10) @Max(300) int windowSeconds,@Min(5) @Max(300) int perAddressLimit,@Min(20) @Max(10000) int globalLimit,@Size(max=8000) String trustedProxyCidrs){}
 private final AuthenticationRateSettingsRepository settings;private final AuthenticationRateBucketRepository buckets;private final WorkspaceRepository workspaces;private final AuditService audit;
 public AuthenticationRateController(AuthenticationRateSettingsRepository settings,AuthenticationRateBucketRepository buckets,WorkspaceRepository workspaces,AuditService audit){this.settings=settings;this.buckets=buckets;this.workspaces=workspaces;this.audit=audit;}
 private Policy view(AuthenticationRateSettings p){return new Policy(p.revision,p.windowSeconds,p.perAddressLimit,p.globalLimit,p.trustedProxyCidrs);}
 @GetMapping public Policy get(){return view(settings.findById(1L).orElseThrow());}
 @PutMapping @Transactional public Policy save(@Valid @RequestBody Update request){
  if(request.globalLimit()<request.perAddressLimit())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Global limit must be at least the per-address limit");try{ProxyAddresses.networks(request.trustedProxyCidrs());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use at most 64 IP-literal CIDRs with nonzero prefixes");}
  workspaces.lockById(1L).orElseThrow();buckets.lockGlobal().orElseThrow();var p=settings.findById(1L).orElseThrow();if(!Objects.equals(p.revision,request.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Authentication policy changed; refresh before saving");
  p.windowSeconds=request.windowSeconds();p.perAddressLimit=request.perAddressLimit();p.globalLimit=request.globalLimit();p.trustedProxyCidrs=Objects.requireNonNullElse(request.trustedProxyCidrs(),"");settings.saveAndFlush(p);audit.record(1L,"AUTHENTICATION_RATE_POLICY_CHANGED",p.revision.toString());return view(p);
 }
}
