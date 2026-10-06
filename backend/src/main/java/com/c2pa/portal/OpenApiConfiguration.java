package com.c2pa.portal;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;
@Configuration
@SecurityScheme(name="workerPairing",type=SecuritySchemeType.HTTP,scheme="bearer",description="Expiring WKR pairing credential for worker-protocol routes only; not a user session")
@SecurityScheme(name="adminToken",type=SecuritySchemeType.APIKEY,in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Admin-Token",description="Session token returned by login; before administrator enrollment only, local bootstrap token")
public class OpenApiConfiguration {
 @org.springframework.context.annotation.Bean public org.springdoc.core.customizers.OpenApiCustomizer workspaceHeader(){return api->api.getPaths().forEach((path,item)->{if(path.startsWith("/api/v1/") && !path.startsWith("/api/v1/worker-protocol/") && !java.util.Set.of("/api/v1/auth/login","/api/v1/auth/status","/api/v1/health").contains(path))item.readOperations().forEach(operation->operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("X-Workspace-Id").in("header").required(false).description("Selected workspace ID. Omit to use your first accessible workspace. Platform administrators may select any workspace.").schema(new io.swagger.v3.oas.models.media.IntegerSchema().format("int64").minimum(java.math.BigDecimal.ONE))));});}
}
