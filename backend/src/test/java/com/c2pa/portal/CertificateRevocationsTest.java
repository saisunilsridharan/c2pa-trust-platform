package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.security.*;
import java.security.cert.*;
import java.time.Instant;
import java.util.*;
import java.nio.file.*;
import java.math.BigInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cert.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
class CertificateRevocationsTest {
 @TempDir Path folder;
 private KeyPair key()throws Exception{var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));return generator.generateKeyPair();}
 private X509Certificate cert(KeyPair subject,X500Name name,KeyPair issuer,X500Name issuerName,boolean ca,BigInteger serial)throws Exception{
  var builder=new JcaX509v3CertificateBuilder(issuerName,serial,Date.from(Instant.now().minusSeconds(3600)),Date.from(Instant.now().plusSeconds(86400)),name,subject.getPublic());builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,new BasicConstraints(ca));builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new KeyUsage(ca?KeyUsage.keyCertSign|KeyUsage.cRLSign:KeyUsage.digitalSignature));return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder(issuer.getPrivate().getAlgorithm().equals("RSA")?"SHA256withRSA":"SHA256withECDSA").build(issuer.getPrivate())));
 }
 private X509CRL crl(KeyPair issuer,X500Name name,BigInteger revoked,boolean expired,String unsupported)throws Exception{
  var builder=new X509v2CRLBuilder(name,Date.from(Instant.now().minusSeconds(3600)));builder.setNextUpdate(Date.from(Instant.now().plusSeconds(expired?-60:3600)));if(revoked!=null)builder.addCRLEntry(revoked,Date.from(Instant.now().minusSeconds(120)),1);if(unsupported!=null)builder.addExtension(new org.bouncycastle.asn1.ASN1ObjectIdentifier(unsupported),true,unsupported.equals("2.5.29.28")?new IssuingDistributionPoint(null,false,false,null,true,false):new org.bouncycastle.asn1.ASN1Integer(1));return new JcaX509CRLConverter().getCRL(builder.build(new JcaContentSignerBuilder(issuer.getPrivate().getAlgorithm().equals("RSA")?"SHA256withRSA":"SHA256withECDSA").build(issuer.getPrivate())));
 }
 private String pem(Object value)throws Exception{var output=new java.io.StringWriter();try(var writer=new JcaPEMWriter(output)){writer.writeObject(value);}return output.toString();}
 private CertificateRevocations service(){return new CertificateRevocations(mock(RevocationSettingsRepository.class),mock(RevocationVersionRepository.class),new ObjectMapper());}
 @Test void verifiesIssuerSignatureCoverageAndRevokedLeaf()throws Exception{
  var issuer=key();var name=new X500Name("CN=Private CA");var root=cert(issuer,name,issuer,name,true,BigInteger.ONE);var signer=key();var leaf=cert(signer,new X500Name("CN=Signer"),issuer,name,false,BigInteger.TWO);var service=service();
  var good=new CertificateRevocations.Configuration(true,true,pem(root),pem(crl(issuer,name,null,false,null)));assertEquals(0,service.summary(service.validate(good)).revokedEntries());assertDoesNotThrow(()->service.check(good,List.of(leaf,root)));
  var revoked=new CertificateRevocations.Configuration(true,true,pem(root),pem(crl(issuer,name,BigInteger.TWO,false,null)));assertEquals(1,service.summary(revoked).revokedEntries());assertThrows(CertificateRevocations.RevokedCertificateException.class,()->service.check(revoked,List.of(leaf,root)));
  var wrong=new CertificateRevocations.Configuration(true,true,pem(root),pem(crl(key(),name,null,false,null)));assertThrows(Exception.class,()->service.validate(wrong));
  var other=key();var otherName=new X500Name("CN=Other CA");var otherRoot=cert(other,otherName,other,otherName,true,BigInteger.valueOf(3));var otherLeaf=cert(key(),new X500Name("CN=Other Signer"),other,otherName,false,BigInteger.valueOf(4));assertThrows(Exception.class,()->service.check(good,List.of(otherLeaf,otherRoot)));assertDoesNotThrow(()->service.check(new CertificateRevocations.Configuration(true,false,good.issuerCertificatesPem(),good.crlsPem()),List.of(otherLeaf,otherRoot)));
 }
 @Test void staleDeltaScopedDuplicateAndUnauthorizedIssuerCrlsFailClosed()throws Exception{
  var issuer=key();var name=new X500Name("CN=Private CA");var root=cert(issuer,name,issuer,name,true,BigInteger.ONE);var service=service();
  for(var crl:List.of(crl(issuer,name,null,true,null),crl(issuer,name,null,false,"2.5.29.27"),crl(issuer,name,null,false,"2.5.29.28"))){var config=new CertificateRevocations.Configuration(true,true,pem(root),pem(crl));assertThrows(Exception.class,()->service.validate(config));assertThrows(Exception.class,()->service.check(config,List.of(root)));}
  var full=pem(crl(issuer,name,null,false,null));assertThrows(Exception.class,()->service.validate(new CertificateRevocations.Configuration(true,true,pem(root),full+full)));
  assertThrows(Exception.class,()->service.validate(new CertificateRevocations.Configuration(true,true,pem(root)+"-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n",full)));
  assertThrows(Exception.class,()->service.validate(new CertificateRevocations.Configuration(true,true,pem(root),full+"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")));
  var weakGenerator=KeyPairGenerator.getInstance("RSA");weakGenerator.initialize(1024);var weak=weakGenerator.generateKeyPair();var weakRoot=cert(weak,name,weak,name,true,BigInteger.ONE);var weakCrl=crl(weak,name,null,false,null);assertThrows(Exception.class,()->service.validate(new CertificateRevocations.Configuration(true,true,pem(weakRoot),pem(weakCrl))));
  var notCa=cert(issuer,name,issuer,name,false,BigInteger.ONE);assertThrows(Exception.class,()->service.validate(new CertificateRevocations.Configuration(true,true,pem(notCa),full)));
 }
 @Test void revokedIntermediateIsRejectedAndInactivePolicyIsNotApplied()throws Exception{
  var rootKey=key();var rootName=new X500Name("CN=Root");var root=cert(rootKey,rootName,rootKey,rootName,true,BigInteger.ONE);var intermediateKey=key();var intermediateName=new X500Name("CN=Intermediate");var intermediate=cert(intermediateKey,intermediateName,rootKey,rootName,true,BigInteger.TWO);var leaf=cert(key(),new X500Name("CN=Signer"),intermediateKey,intermediateName,false,BigInteger.valueOf(3));
  var config=new CertificateRevocations.Configuration(true,true,pem(root)+pem(intermediate),pem(crl(rootKey,rootName,BigInteger.TWO,false,null))+pem(crl(intermediateKey,intermediateName,null,false,null)));assertThrows(CertificateRevocations.RevokedCertificateException.class,()->service().check(config,List.of(leaf,intermediate,root)));
  var settings=mock(RevocationSettingsRepository.class);var versions=mock(RevocationVersionRepository.class);var mapper=new ObjectMapper();var service=new CertificateRevocations(settings,versions,mapper);var active=new RevocationSettings();active.id=2L;active.activeVersion="policy";when(settings.findById(2L)).thenReturn(Optional.of(active));var version=new RevocationVersion();version.workspaceId=2L;version.configuration=mapper.writeValueAsString(config);when(versions.findById("policy")).thenReturn(Optional.of(version));var path=folder.resolve("chain.pem");Files.writeString(path,pem(leaf)+pem(intermediate)+pem(root));assertThrows(Exception.class,()->service.enforce(2L,path));assertDoesNotThrow(()->service.enforce(3L,path));
  var execution=new WorkerExecution(mock(HardwareSigning.class),mock(TrustPolicy.class),mock(PrivateTimestamps.class),mock(WorkerSandbox.class),service);assertThrows(Exception.class,()->execution.sign(2L,Path.of("missing-worker"),path,folder.resolve("output"),path,path,"missing-key",folder.resolve("report"),folder.resolve("error"),30,null,null,new WorkerSandbox.Budget(512,30,"LIMITS")));assertFalse(Files.exists(folder.resolve("output")));
 }
}
