package com.c2pa.portal;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;
@Configuration
@SecurityScheme(name="adminToken",type=SecuritySchemeType.APIKEY,in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Admin-Token")
public class OpenApiConfiguration {}
