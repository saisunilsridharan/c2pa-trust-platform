package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.io.*;
import java.util.*;
@Service public class HardwareCertificateRequests {
 public record Subject(String commonName,String organization,String country){}
 private final HardwareSigning hardware;
 public HardwareCertificateRequests(HardwareSigning hardware){this.hardware=hardware;}
 public X509Certificate certificate(HardwareIdentity identity)throws Exception{
  try(var input=Files.newInputStream(Path.of(identity.certificatePath))){var certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())).equals(identity.fingerprint))throw new IllegalStateException();return certificate;}
 }
 public static void validateSubject(Subject subject,boolean dnsSubject){
  if(subject==null || subject.commonName()==null || subject.commonName().isBlank() || subject.commonName().length()>200 || (subject.organization()!=null && subject.organization().length()>200) || (subject.country()!=null && !subject.country().isBlank() && !subject.country().matches("[A-Z]{2}")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide a common name, optional organization and two-letter uppercase country code");
  if(dnsSubject){String dns=subject.commonName().trim();if(!dns.matches("(?=.{1,200}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*") || dns.matches("[0-9.]+"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The private-CA connector requires a DNS-style common name");}
 }
 public byte[] csr(HardwareIdentity identity,Subject subject)throws Exception{return csr(identity,subject,false);}
 public byte[] csr(HardwareIdentity identity,Subject subject,boolean dnsSubject)throws Exception{
  validateSubject(subject,dnsSubject);
  var name=new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN,subject.commonName().trim());if(subject.organization()!=null && !subject.organization().isBlank())name.addRDN(BCStyle.O,subject.organization().trim());if(subject.country()!=null && !subject.country().isBlank())name.addRDN(BCStyle.C,subject.country());
  var old=certificate(identity);var builder=new JcaPKCS10CertificationRequestBuilder(name.build(),old.getPublicKey());
  var extensions=new ExtensionsGenerator();extensions.addExtension(Extension.basicConstraints,true,new BasicConstraints(false));extensions.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.digitalSignature));extensions.addExtension(Extension.extendedKeyUsage,false,new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.getInstance(new ASN1ObjectIdentifier("1.3.6.1.4.1.62558.2.1")),KeyPurposeId.id_kp_emailProtection}));
  if(dnsSubject){String dns=subject.commonName().trim();extensions.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.dNSName,dns)));}
  builder.addAttribute(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.pkcs_9_at_extensionRequest,extensions.generate());
  var data=new ByteArrayOutputStream();ContentSigner signer=new ContentSigner(){public AlgorithmIdentifier getAlgorithmIdentifier(){return new AlgorithmIdentifier(org.bouncycastle.asn1.x9.X9ObjectIdentifiers.ecdsa_with_SHA256);}public OutputStream getOutputStream(){return data;}public byte[] getSignature(){try{return hardware.sign(identity.id,identity.workspaceId,data.toByteArray());}catch(Exception e){throw new IllegalStateException("Hardware CSR signing failed",e);}}};
  var request=builder.build(signer);if(!request.isSignatureValid(new JcaContentVerifierProviderBuilder().build(old.getPublicKey())))throw new IllegalStateException("CSR proof of possession failed");
  return ("-----BEGIN CERTIFICATE REQUEST-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(request.getEncoded())+"\n-----END CERTIFICATE REQUEST-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
 }
}
