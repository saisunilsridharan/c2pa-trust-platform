package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
@Service public class OidcService {
 public record Configuration(boolean enabled,String issuer,String clientId,String clientSecretCredential,String redirectUri,boolean allowLoopbackHttp,String trustedCaPem){}
 public record Discovery(String issuer,String authorizationEndpoint,String tokenEndpoint,String jwksUri){}
 public record Started(String authorizationUrl,String browserProof,boolean secureCookie){}
 private final OidcSettingsRepository settings;private final OidcVersionRepository versions;private final OidcTransactionRepository transactions;private final OidcTransactions consumption;private final OidcLinkRepository links;private final CredentialService credentials;private final ObjectMapper mapper;private final AccountService accounts;
 public OidcService(OidcSettingsRepository settings,OidcVersionRepository versions,OidcTransactionRepository transactions,OidcTransactions consumption,OidcLinkRepository links,CredentialService credentials,ObjectMapper mapper,AccountService accounts){this.settings=settings;this.versions=versions;this.transactions=transactions;this.consumption=consumption;this.links=links;this.credentials=credentials;this.mapper=mapper;this.accounts=accounts;}
 public Configuration decode(String value)throws Exception{return mapper.readValue(value,Configuration.class);}
 private URI endpoint(String value,boolean allowHttp)throws Exception {URI uri=new URI(value);if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || value.length()>2000)throw new IllegalArgumentException();ServiceEndpoint.validate(new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toString(),allowHttp);return uri;}
 public Configuration validate(Configuration c)throws Exception {
  if(c==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);if(!c.enabled())return new Configuration(false,null,null,null,null,false,null);
  try{endpoint(c.issuer(),c.allowLoopbackHttp());URI callback=endpoint(c.redirectUri(),c.allowLoopbackHttp());if(!callback.getPath().equals("/oidc/callback") || c.issuer().endsWith("/") || c.issuer().length()>800 || !c.issuer().chars().allMatch(ch->ch>32 && ch<127) || c.clientId()==null || c.clientId().isBlank() || c.clientId().length()>200 || (c.trustedCaPem()!=null && c.trustedCaPem().length()>12000))throw new IllegalArgumentException();PrivateObjectStorage.trust(c.trustedCaPem());if(c.clientSecretCredential()!=null && !c.clientSecretCredential().isBlank()){byte[] secret=credentials.read(1L,c.clientSecretCredential());Arrays.fill(secret,(byte)0);}return c;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid private OIDC issuer, callback, client or TLS configuration");}
 }
 private boolean sameOrigin(URI a,URI b){return a.getScheme().equals(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort()==b.getPort();}
 private JsonNode exchange(Configuration c,URI endpoint,String form)throws Exception {
  var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);var trust=PrivateObjectStorage.trust(c.trustedCaPem());if(trust!=null){var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(null,trust,null);builder.sslContext(ssl);}
  try(var client=builder.build()){
   var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Accept","application/json");if(form==null)request.GET();else request.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));var response=client.send(request.build(),info->new BoundedHttpBody(65536));if(response.statusCode()!=200)throw new IllegalStateException();return mapper.readTree(response.body());
  }
 }
 public Discovery discover(Configuration configuration)throws Exception {
  var c=validate(configuration);if(!c.enabled())return null;URI issuer=endpoint(c.issuer(),c.allowLoopbackHttp());JsonNode document=exchange(c,endpoint(c.issuer()+"/.well-known/openid-configuration",c.allowLoopbackHttp()),null);
  if(!c.issuer().equals(document.path("issuer").asText()) || !document.path("code_challenge_methods_supported").isArray() || !document.path("code_challenge_methods_supported").toString().contains("\"S256\""))throw new IllegalArgumentException();
  String authorization=document.path("authorization_endpoint").asText(),token=document.path("token_endpoint").asText(),jwks=document.path("jwks_uri").asText();for(String value:List.of(authorization,token,jwks))if(!sameOrigin(issuer,endpoint(value,c.allowLoopbackHttp())))throw new IllegalArgumentException();JWKSet keys=JWKSet.parse(exchange(c,endpoint(jwks,c.allowLoopbackHttp()),null).toString());if(keys.getKeys().stream().noneMatch(k->k instanceof RSAKey && (k.getKeyUse()==null || k.getKeyUse().equals(KeyUse.SIGNATURE))))throw new IllegalArgumentException();return new Discovery(c.issuer(),authorization,token,jwks);
 }
 public void test(Configuration c)throws Exception {try{discover(c);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"OIDC discovery/key test failed; check TLS, issuer, PKCE and receiver metadata");}}
 private String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
 private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 @Transactional public Started start()throws Exception {
  var selected=settings.findById(1L).filter(s->s.activeVersion!=null).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Private login is not configured"));var version=versions.findById(selected.activeVersion).orElseThrow();var c=decode(version.configuration);if(!c.enabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Private login is disabled");var discovery=discover(c);String state=random(),browser=random(),verifier=random(),nonce=random();String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  var transaction=new OidcTransaction();transaction.id=AccountService.hash(state);transaction.browserHash=AccountService.hash(browser);transaction.versionId=version.id;transaction.proofCredential=credentials.createProtected("OIDC transaction proof",mapper.writeValueAsString(Map.of("verifier",verifier,"nonce",nonce))).id;transaction.expiresAt=Instant.now().plusSeconds(600);transactions.saveAndFlush(transaction);
  String url=discovery.authorizationEndpoint()+"?response_type=code&scope=openid&client_id="+encode(c.clientId())+"&redirect_uri="+encode(c.redirectUri())+"&state="+state+"&nonce="+nonce+"&code_challenge="+challenge+"&code_challenge_method=S256";return new Started(url,browser,URI.create(c.redirectUri()).getScheme().equals("https"));
 }
 public AccountService.Login complete(String state,String browser,String code,String secondFactor)throws Exception {
  var transaction=consumption.consume(state,browser);try{
   if(code==null || code.isBlank() || code.length()>2048)throw new IllegalArgumentException();var c=decode(versions.findById(transaction.versionId).orElseThrow().configuration);var discovery=discover(c);byte[] bytes=credentials.readProtected(transaction.proofCredential);JsonNode proof;try{proof=mapper.readTree(bytes);}finally{Arrays.fill(bytes,(byte)0);}
   String form="grant_type=authorization_code&client_id="+encode(c.clientId())+"&redirect_uri="+encode(c.redirectUri())+"&code="+encode(code)+"&code_verifier="+encode(proof.path("verifier").asText());if(c.clientSecretCredential()!=null && !c.clientSecretCredential().isBlank()){byte[] secret=credentials.read(1L,c.clientSecretCredential());try{form+="&client_secret="+encode(new String(secret,StandardCharsets.UTF_8));}finally{Arrays.fill(secret,(byte)0);}}
   String token=exchange(c,endpoint(discovery.tokenEndpoint(),c.allowLoopbackHttp()),form).path("id_token").asText();if(token.length()>32768)throw new IllegalArgumentException();SignedJWT jwt=SignedJWT.parse(token);if(!jwt.getHeader().getAlgorithm().equals(JWSAlgorithm.RS256) || jwt.getHeader().getCriticalParams()!=null)throw new IllegalArgumentException();var key=JWKSet.parse(exchange(c,endpoint(discovery.jwksUri(),c.allowLoopbackHttp()),null).toString()).getKeyByKeyId(jwt.getHeader().getKeyID());if(!(key instanceof RSAKey rsa) || (rsa.getKeyUse()!=null && !rsa.getKeyUse().equals(KeyUse.SIGNATURE)) || (rsa.getAlgorithm()!=null && !rsa.getAlgorithm().equals(JWSAlgorithm.RS256)) || rsa.toRSAPublicKey().getModulus().bitLength()<2048 || !jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey())))throw new IllegalArgumentException();var claims=jwt.getJWTClaimsSet();Instant now=Instant.now();
   if(!c.issuer().equals(claims.getIssuer()) || !claims.getAudience().contains(c.clientId()) || (claims.getAudience().size()>1 && !c.clientId().equals(claims.getStringClaim("azp"))) || claims.getExpirationTime()==null || !claims.getExpirationTime().toInstant().isAfter(now) || claims.getIssueTime()==null || claims.getIssueTime().toInstant().isAfter(now.plusSeconds(30)) || (claims.getNotBeforeTime()!=null && claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(30))) || !MessageDigest.isEqual(proof.path("nonce").asText().getBytes(StandardCharsets.US_ASCII),Objects.requireNonNullElse(claims.getStringClaim("nonce"),"").getBytes(StandardCharsets.US_ASCII)) || claims.getSubject()==null || claims.getSubject().isBlank() || claims.getSubject().length()>255)throw new IllegalArgumentException();
   var link=links.findByIssuerAndSubject(c.issuer(),claims.getSubject()).orElseThrow();var login=accounts.loginExternal(link.userId,secondFactor);if(login==null)throw new IllegalArgumentException();return login;
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Private login failed; verify account linking and second factor, then start a fresh login");}
 }
 public boolean enabled(){try{return settings.findById(1L).filter(s->s.activeVersion!=null).flatMap(s->versions.findById(s.activeVersion)).map(v->{try{return decode(v.configuration).enabled();}catch(Exception e){return false;}}).orElse(false);}catch(Exception e){return false;}}
}
