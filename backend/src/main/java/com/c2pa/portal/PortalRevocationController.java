package com.c2pa.portal;
@org.springframework.web.bind.annotation.RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class PortalRevocationController {
 private final CertificateRevocations revocations;
 public PortalRevocationController(CertificateRevocations revocations){this.revocations=revocations;}
 @org.springframework.web.bind.annotation.GetMapping("/api/v1/portal/revocation")
 public CertificateRevocations.Status status()throws Exception{return revocations.status(WorkspaceContext.id());}
}
