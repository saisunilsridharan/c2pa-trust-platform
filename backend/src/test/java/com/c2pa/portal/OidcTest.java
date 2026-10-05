package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:oidc","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-","portal.jobs.recovery.schedule=-","portal.webhooks.schedule=-"})
class OidcTest {
 @Autowired OidcService service;@Autowired OidcVersionRepository versions;@Autowired OidcSettingsRepository settings;@Autowired OidcLinkRepository links;@Autowired AccountService accounts;@Autowired ObjectMapper mapper;
 @MockitoBean CredentialService credentials;
 private Map<String,String> form(String value){Map<String,String> result=new HashMap<>();for(String part:value.split("&")){String[] pair=part.split("=",2);result.put(java.net.URLDecoder.decode(pair[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8));}return result;}
 @Test void pkceBrowserBindingJwtValidationAccountLinkingAndReplay()throws Exception {
  Map<String,String> secrets=new ConcurrentHashMap<>();when(credentials.createProtected(anyString(),anyString())).thenAnswer(i->{var c=new EncryptedCredential();c.id=UUID.randomUUID().toString();secrets.put(c.id,i.getArgument(1));return c;});when(credentials.readProtected(anyString())).thenAnswer(i->secrets.get(i.getArgument(0)).getBytes(StandardCharsets.UTF_8));
  RSAKey key=new RSAKeyGenerator(2048).keyID("fixture-key").generate(),forged=new RSAKeyGenerator(2048).keyID("fixture-key").generate();var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);String origin="http://127.0.0.1:"+server.getAddress().getPort(),issuer=origin+"/realm";AtomicReference<String> mode=new AtomicReference<>("valid");Map<String,Map<String,String>> grants=new ConcurrentHashMap<>();
  server.createContext("/realm/.well-known/openid-configuration",request->{byte[] body=mapper.writeValueAsBytes(Map.of("issuer",issuer,"authorization_endpoint",origin+"/authorize","token_endpoint",origin+"/token","jwks_uri",origin+"/keys","code_challenge_methods_supported",List.of("S256")));request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);request.close();});
  server.createContext("/keys",request->{byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);request.close();});
  server.createContext("/token",request->{try{var values=form(new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var grant=grants.remove(values.get("code"));String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(values.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));if(grant==null || !challenge.equals(grant.get("code_challenge")) || !"portal-oidc".equals(values.get("client_id")) || !values.get("redirect_uri").equals(grant.get("redirect_uri"))){request.sendResponseHeaders(400,-1);return;}
   Instant now=Instant.now();String test=mode.get();var claims=new JWTClaimsSet.Builder().issuer(test.equals("issuer")?origin+"/wrong":issuer).subject(test.equals("unlinked")?"unknown-subject":"known-subject").audience(test.equals("audience")?"wrong-client":"portal-oidc").issueTime(Date.from(now)).expirationTime(Date.from(test.equals("expired")?now.minusSeconds(1):now.plusSeconds(120))).claim("nonce",test.equals("nonce")?"wrong-nonce":grant.get("nonce")).build();var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture-key").build(),claims);jwt.sign(new RSASSASigner(test.equals("signature")?forged:key));byte[] body=mapper.writeValueAsBytes(Map.of("id_token",jwt.serialize()));request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);
  }catch(Exception e){request.sendResponseHeaders(500,-1);}finally{request.close();}});server.start();
  try{
   var config=new OidcService.Configuration(true,issuer,"portal-oidc",null,"http://127.0.0.1:5173/oidc/callback",true,null);service.test(config);var version=new OidcVersion();version.id=UUID.randomUUID().toString();version.configuration=mapper.writeValueAsString(config);version.createdAt=Instant.now();version.testedAt=Instant.now();versions.saveAndFlush(version);var setting=new OidcSettings();setting.activeVersion=version.id;settings.saveAndFlush(setting);String password=UUID.randomUUID()+"x";var user=accounts.create("oidc-fixture",password,"SIGNER");var link=new OidcLink();link.userId=user.id;link.issuer=issuer;link.subject="known-subject";links.saveAndFlush(link);
   var started=service.start();assertFalse(started.secureCookie());var grant=form(java.net.URI.create(started.authorizationUrl()).getRawQuery());assertEquals("S256",grant.get("code_challenge_method"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.complete(grant.get("state"),"0".repeat(64),"unissued",null));String code=UUID.randomUUID().toString();grants.put(code,grant);var session=service.complete(grant.get("state"),started.browserProof(),code,null);assertEquals(user.id,session.user().id());assertTrue(accounts.authenticate(session.token()).isPresent());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.complete(grant.get("state"),started.browserProof(),code,null));
   for(String invalid:List.of("issuer","audience","nonce","expired","signature","unlinked")){mode.set(invalid);var next=service.start();var data=form(java.net.URI.create(next.authorizationUrl()).getRawQuery());String nextCode=UUID.randomUUID().toString();grants.put(nextCode,data);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.complete(data.get("state"),next.browserProof(),nextCode,null),invalid);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.complete(data.get("state"),next.browserProof(),nextCode,null),"Failed responses consume state");}
   mode.set("valid");user=usersDisable(user.id);var disabled=service.start();var data=form(java.net.URI.create(disabled.authorizationUrl()).getRawQuery());String disabledCode=UUID.randomUUID().toString();grants.put(disabledCode,data);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.complete(data.get("state"),disabled.browserProof(),disabledCode,null));
  }finally{server.stop(0);}
 }
 @Autowired UserRepository users;
 private PortalUser usersDisable(Long id){var user=users.findById(id).orElseThrow();user.enabled=false;return users.saveAndFlush(user);}
}
