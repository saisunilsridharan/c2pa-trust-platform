package com.c2pa.portal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
@Component @Order(Ordered.HIGHEST_PRECEDENCE+20)
public class RemoteWorkerAuthentication extends OncePerRequestFilter {
 private final RemoteWorkerSecurity security;
 public RemoteWorkerAuthentication(RemoteWorkerSecurity security){this.security=security;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
  String path=request.getRequestURI().replaceAll(";[^/]*","").replaceAll("/+","/");
  if(!path.startsWith("/api/v1/worker-protocol/")){chain.doFilter(request,response);return;}
  String value=request.getHeader("Authorization");var worker=security.authenticate(value!=null && value.startsWith("Bearer ")?value.substring(7):null);
  if(worker.isEmpty()){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"error\":\"Worker authentication required\"}");return;}
  request.setAttribute("portal.workerId",worker.get().id);request.setAttribute("portal.workerTokenHash",worker.get().tokenHash);request.setAttribute("portal.workspaceId",worker.get().workspaceId);
  chain.doFilter(request,response);
 }
}
