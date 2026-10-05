package com.c2pa.portal;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;
@Configuration
@SecurityScheme(name="adminToken",type=SecuritySchemeType.APIKEY,in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Admin-Token",description="Session token returned by login; before administrator enrollment only, local bootstrap token")
public class OpenApiConfiguration {}
