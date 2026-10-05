package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContentFormatsTest {
 @Test void detectsContentInsteadOfClientMetadata()throws Exception {
  Map<String,byte[]> cases=new LinkedHashMap<>();
  cases.put("image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255});
  cases.put("image/png",new byte[]{(byte)137,80,78,71,13,10,26,10});
  for(var pair:List.of(new String[]{"image/webp","RIFF0000WEBP"},new String[]{"audio/wav","RIFF0000WAVE"},new String[]{"image/tiff","II*\0"},new String[]{"audio/flac","fLaC"},new String[]{"audio/mpeg","ID3"},new String[]{"video/mp4","0000ftypisom"},new String[]{"application/pdf","%PDF-1.7"}))cases.put(pair[0],pair[1].getBytes(StandardCharsets.ISO_8859_1));
  for(var pair:cases.entrySet()){var upload=new MockMultipartFile("file","misleading.exe","application/octet-stream",pair.getValue());assertEquals(pair.getKey(),ContentFormats.detect(upload));assertTrue(ContentFormats.extension(pair.getKey()).startsWith("."));}
  for(String invalid:List.of("plain text","0000ftypheic","RIFF0000AVI ","RIFF"))assertNull(ContentFormats.detect(new MockMultipartFile("file","image.png","image/png",invalid.getBytes(StandardCharsets.ISO_8859_1))));
 }
}
