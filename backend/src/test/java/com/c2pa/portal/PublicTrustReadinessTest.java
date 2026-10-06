package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
class PublicTrustReadinessTest {
 @Test void onlineActivationRequiresPositiveProbeAndNegativeProbeClearsReadiness()throws Exception{
  var mapper=new ObjectMapper();var settings=mock(PublicTrustSettingsRepository.class);var versions=mock(PublicTrustVersionRepository.class);var trust=mock(OfficialPublicTrust.class);var workspaces=mock(WorkspaceRepository.class);
  var selected=new PublicTrustSettings();selected.id=1L;selected.revision=1L;selected.draftVersion="fixture";var version=new PublicTrustVersion();version.id="fixture";version.workspaceId=1L;version.configuration="fixture-config";
  when(settings.findById(1L)).thenReturn(Optional.of(selected));when(versions.findById("fixture")).thenReturn(Optional.of(version));when(workspaces.lockById(1L)).thenReturn(Optional.of(new Workspace()));
  var online=new OfficialPublicTrust.Configuration(true,24,"","",false,new CertificateRevocations.Configuration(true,true,"","",new OnlineOcsp.Configuration("https://responder.example/status","",false)));
  when(trust.decode(version.configuration)).thenReturn(online);when(trust.summary(version)).thenReturn(new OfficialPublicTrust.Summary(true,true,1,1,Instant.now().plusSeconds(3600),"signer-hash","tsa-hash"));
  var controller=new PublicTrustController(settings,versions,trust,workspaces,mapper,mock(AuditService.class));var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);var file=new org.springframework.mock.web.MockMultipartFile("file","fixture.png","image/png",bytes.toByteArray());
  WorkspaceContext.call(1L,()->{
   when(trust.evaluate(eq(version),any(),any())).thenReturn(mapper.createObjectNode().put("publicTrustVerified",false));controller.test(1L,"fixture",false,file);assertNull(version.testedAt);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.activate(new PublicTrustController.Selection(1L,"fixture",true)));
   when(trust.evaluate(eq(version),any(),any())).thenReturn(mapper.createObjectNode().put("publicTrustVerified",true));controller.test(1L,"fixture",true,file);assertNotNull(version.testedAt);assertEquals("fixture",controller.activate(new PublicTrustController.Selection(1L,"fixture",true)).active().id());
   when(trust.evaluate(eq(version),any(),any())).thenReturn(mapper.createObjectNode().put("publicTrustVerified",false));controller.test(1L,"fixture",false,file);assertNull(version.testedAt);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.activate(new PublicTrustController.Selection(1L,"fixture",true)));
   when(trust.decode(version.configuration)).thenReturn(new OfficialPublicTrust.Configuration(true,24,"","",false));controller.test(1L,"fixture",false,file);assertNotNull(version.testedAt);return null;
  });
 }
}
