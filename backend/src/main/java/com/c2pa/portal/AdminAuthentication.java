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
 private final AccountService accounts;
 public AdminAuthentication(AccountService accounts) throws IOException {
  this.accounts=accounts;
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
  String rawPath=request.getServletPath();if(rawPath.isEmpty())rawPath=request.getRequestURI();
  String path=rawPath.replaceAll(";[^/]*", "").replaceAll("/+", "/");
  if(!path.startsWith("/api/") || path.equals("/api/v1/health") || path.equals("/api/v1/auth/status") || path.equals("/api/v1/auth/login")){chain.doFilter(request,response);return;}
  String supplied=request.getHeader("X-Admin-Token");
  String authorization=request.getHeader("Authorization");
  if(authorization!=null && authorization.startsWith("Bearer "))supplied=authorization.substring(7);
  boolean bootstrap=!accounts.enrolled() && supplied!=null && MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8));
  var user=bootstrap?java.util.Optional.<PortalUser>empty():accounts.authenticate(supplied);
  if(!bootstrap && user.isEmpty()){reject(response,401,"Authentication required");return;}
  if(user.isPresent() && user.get().passwordChangeRequired && !java.util.Set.of("/api/v1/auth/me","/api/v1/auth/logout","/api/v1/auth/password").contains(path)){reject(response,403,"Change your temporary password before accessing the portal");return;}
  String role=bootstrap?"ADMIN":user.get().role;
  boolean ordinary=java.util.Set.of("/api/v1/auth/me","/api/v1/auth/logout","/api/v1/auth/password","/api/v1/portal/configuration","/api/v1/verification").contains(path) || (path.equals("/api/v1/signing") && role.equals("SIGNER"));
  if(path.startsWith("/api/v1/jobs"))ordinary=role.equals("SIGNER") || (role.equals("VIEWER") && request.getMethod().equals("GET"));
  if((!role.equals("ADMIN") && !ordinary) || (path.startsWith("/api/v1/admin/") && !role.equals("ADMIN")) || (path.equals("/api/v1/signing") && role.equals("VIEWER")) || (path.equals("/api/v1/auth/enroll") && !bootstrap)) {reject(response,403,"Permission denied");return;}
  request.setAttribute("portal.actor",bootstrap?"bootstrap-administrator":user.get().username);
  request.setAttribute("portal.role",role);request.setAttribute("portal.userId",bootstrap?null:user.get().id);
  request.setAttribute("portal.sessionToken",supplied);
  chain.doFilter(request,response);
 }
 private void reject(HttpServletResponse response,int code,String message) throws IOException {
  response.setStatus(code);response.setContentType("application/json");response.getWriter().write("{\"error\":\""+message+"\"}");
 }
}
