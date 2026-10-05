package com.c2pa.portal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
@Component
public class AdminAuthentication extends OncePerRequestFilter {
 private final byte[] token;
 public AdminAuthentication() throws IOException {
  Path directory=Path.of(".local"); Files.createDirectories(directory);
  Path file=directory.resolve("admin-token");
  if (!Files.exists(file)) {
   byte[] random=new byte[32]; new SecureRandom().nextBytes(random);
   Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
   Files.writeString(file, HexFormat.of().formatHex(random));
  }
  String value=Files.readString(file).trim();
  if(!value.matches("[0-9a-f]{64}"))throw new IOException("Invalid local administrator credential file");
  token=value.getBytes(StandardCharsets.UTF_8);
 }
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
  if(request.getRequestURI().startsWith("/api/") && !request.getRequestURI().equals("/api/v1/health")) {
   String supplied=request.getHeader("X-Admin-Token");
   if(supplied==null || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8))) {
    response.setStatus(401); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"Administrator authentication required\"}"); return;
   }
  }
  chain.doFilter(request,response);
 }
}
