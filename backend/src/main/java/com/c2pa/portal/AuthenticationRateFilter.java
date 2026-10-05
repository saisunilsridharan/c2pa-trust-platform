package com.c2pa.portal;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
public class AuthenticationRateFilter extends OncePerRequestFilter {
 private final AuthenticationRateLimiter limiter;
 public AuthenticationRateFilter(AuthenticationRateLimiter limiter){this.limiter=limiter;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String raw=request.getServletPath();if(raw.isEmpty())raw=request.getRequestURI();
  String path=raw.replaceAll(";[^/]*", "").replaceAll("/+", "/");
  if(!request.getMethod().equals("POST") || !Set.of("/api/v1/auth/login","/api/v1/auth/recovery","/api/v1/auth/oidc/start","/api/v1/auth/oidc/complete","/api/v1/auth/enroll").contains(path)){chain.doFilter(request,response);return;}
  AuthenticationRateLimiter.Decision decision;
  try{var headers=Collections.list(request.getHeaders("X-Forwarded-For"));decision=limiter.admit(request.getRemoteAddr(),headers.size()==1?headers.getFirst():null);}catch(Exception e){response.setStatus(503);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write("{\"error\":\"Authentication is temporarily unavailable\"}");return;}
  if(!decision.allowed()){response.setStatus(429);response.setHeader("Retry-After",Integer.toString(decision.retryAfter()));response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write("{\"error\":\"Too many authentication requests. Try again later.\"}");return;}
  chain.doFilter(request,response);
 }
}
