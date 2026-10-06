package com.c2pa.portal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.security.*;
import java.security.cert.*;
import java.time.Instant;
import java.math.BigInteger;
import java.util.*;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.cert.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cert.ocsp.*;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.operator.jcajce.*;

class OnlineOcspTest {
 private KeyPair key()throws Exception{var g=KeyPairGenerator.getInstance("EC");g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
 private X509Certificate cert(KeyPair key,String name,KeyPair issuer,String issuerName,boolean ca,boolean responder,BigInteger serial)throws Exception{
  var b=new JcaX509v3CertificateBuilder(new X500Name(issuerName),serial,Date.from(Instant.now().minusSeconds(3600)),Date.from(Instant.now().plusSeconds(86400)),new X500Name(name),key.getPublic());
  b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,new BasicConstraints(ca));
  b.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new KeyUsage(ca?KeyUsage.keyCertSign|KeyUsage.cRLSign:KeyUsage.digitalSignature));
  if(responder)b.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_OCSPSigning));
  return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuer.getPrivate())));
 }
 private CertificateID id(X509Certificate issuer,BigInteger serial)throws Exception{return new CertificateID(new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1),new JcaX509CertificateHolder(issuer),serial);}
 private byte[] response(KeyPair signer,X509Certificate signerCert,CertificateID id,byte[] nonce,CertificateStatus status,int age,boolean next,boolean weak)throws Exception{
  var b=new JcaBasicOCSPRespBuilder(signer.getPublic(),new JcaDigestCalculatorProviderBuilder().build().get(RespID.HASH_SHA1));
  b.addResponse(id,status,Date.from(Instant.now().minusSeconds(age)),next?Date.from(Instant.now().plusSeconds(120)):null,null);
  if(nonce!=null)b.setResponseExtensions(new Extensions(new org.bouncycastle.asn1.x509.Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce,false,new DEROctetString(nonce).getEncoded())));
  var basic=b.build(new JcaContentSignerBuilder(weak?"SHA1withECDSA":"SHA256withECDSA").build(signer.getPrivate()),new X509CertificateHolder[]{new JcaX509CertificateHolder(signerCert)},new Date());
  return new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL,basic).getEncoded();
 }
 private X509CRL crl(KeyPair issuer,X509Certificate cert,BigInteger revoked)throws Exception{
  var b=new X509v2CRLBuilder(new JcaX509CertificateHolder(cert).getSubject(),Date.from(Instant.now().minusSeconds(60)));b.setNextUpdate(Date.from(Instant.now().plusSeconds(3600)));
  if(revoked!=null)b.addCRLEntry(revoked,Date.from(Instant.now().minusSeconds(60)),1);
  return new JcaX509CRLConverter().getCRL(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuer.getPrivate())));
 }
 @Test void validatesIssuerSignatureNonceCertificateAndFreshness()throws Exception{
  var issuer=key();var root=cert(issuer,"CN=Issuer",issuer,"CN=Issuer",true,false,BigInteger.ONE);var id=id(root,BigInteger.TWO);byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
  assertDoesNotThrow(()->OnlineOcsp.verify(response(issuer,root,id,nonce,null,10,true,false),id,nonce,root,List.of()));
  assertThrows(CertificateRevocations.RevokedCertificateException.class,()->OnlineOcsp.verify(response(issuer,root,id,nonce,new RevokedStatus(Date.from(Instant.now().minusSeconds(30)),1),10,true,false),id,nonce,root,List.of()));
  assertThrows(Exception.class,()->OnlineOcsp.verify(response(issuer,root,id,nonce,new UnknownStatus(),10,true,false),id,nonce,root,List.of()));
  for(var body:List.of(response(issuer,root,id,null,null,10,true,false),response(issuer,root,id,new byte[16],null,10,true,false),response(issuer,root,id(root,BigInteger.TEN),nonce,null,10,true,false),response(issuer,root,id,nonce,null,1000,true,false),response(issuer,root,id,nonce,null,10,false,false),response(issuer,root,id,nonce,null,10,true,true))){assertThrows(Exception.class,()->OnlineOcsp.verify(body,id,nonce,root,List.of()));}
  var outsider=key();assertThrows(Exception.class,()->OnlineOcsp.verify(response(outsider,root,id,nonce,null,10,true,false),id,nonce,root,List.of()));
 }
 @Test void delegatedResponderRequiresIssuerAuthorizationAndCurrentCrlCoverage()throws Exception{
  var issuer=key();var root=cert(issuer,"CN=Issuer",issuer,"CN=Issuer",true,false,BigInteger.ONE);var id=id(root,BigInteger.TWO);byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
  var signer=key();var delegated=cert(signer,"CN=Responder",issuer,"CN=Issuer",false,true,BigInteger.TEN);var body=response(signer,delegated,id,nonce,null,10,true,false);
  assertDoesNotThrow(()->OnlineOcsp.verify(body,id,nonce,root,List.of(crl(issuer,root,null))));
  assertThrows(Exception.class,()->OnlineOcsp.verify(body,id,nonce,root,List.of()));
  assertThrows(CertificateRevocations.RevokedCertificateException.class,()->OnlineOcsp.verify(body,id,nonce,root,List.of(crl(issuer,root,BigInteger.TEN))));
  var unauthorized=cert(signer,"CN=Responder",issuer,"CN=Issuer",false,false,BigInteger.TEN);assertThrows(Exception.class,()->OnlineOcsp.verify(response(signer,unauthorized,id,nonce,null,10,true,false),id,nonce,root,List.of(crl(issuer,root,null))));
 }
 @Test void httpTransportPostsNonceAndRejectsRedirectsAndWrongContentType()throws Exception{
  var issuer=key();var root=cert(issuer,"CN=Issuer",issuer,"CN=Issuer",true,false,BigInteger.ONE);var leaf=cert(key(),"CN=Leaf",issuer,"CN=Issuer",false,false,BigInteger.TWO);
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  var mode=new java.util.concurrent.atomic.AtomicInteger();
  server.createContext("/ocsp",exchange->{try{
   var request=new OCSPReq(exchange.getRequestBody().readAllBytes());var nonce=ASN1OctetString.getInstance(request.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce).getParsedValue()).getOctets();
   var body=response(issuer,root,request.getRequestList()[0].getCertID(),nonce,null,10,true,false);
   exchange.getResponseHeaders().set("Content-Type",mode.get()==2?"application/json":"application/ocsp-response");
   exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/ocsp");exchange.sendResponseHeaders(mode.get()==1?302:200,body.length);exchange.getResponseBody().write(body);
  }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});server.start();
  try{
   var config=new OnlineOcsp.Configuration("http://127.0.0.1:"+server.getAddress().getPort()+"/ocsp",null,true);
   assertDoesNotThrow(()->OnlineOcsp.check(config,leaf,root,List.of()));
   mode.set(1);assertThrows(Exception.class,()->OnlineOcsp.check(config,leaf,root,List.of()));mode.set(2);assertThrows(Exception.class,()->OnlineOcsp.check(config,leaf,root,List.of()));
   assertThrows(Exception.class,()->OnlineOcsp.validate(new OnlineOcsp.Configuration(config.endpoint(),null,false)));
   assertThrows(Exception.class,()->OnlineOcsp.validate(new OnlineOcsp.Configuration("http://169.254.169.254/ocsp",null,true)));
  }finally{server.stop(0);}
 }
}
