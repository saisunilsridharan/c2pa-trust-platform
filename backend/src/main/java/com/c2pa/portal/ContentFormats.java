package com.c2pa.portal;

import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Format detection uses bytes, never an upload's filename or supplied MIME type. */
public final class ContentFormats {
 private ContentFormats(){}
 public record Capability(String mime,String label,String extension,boolean signing,boolean inspection){}
 public static final List<Capability> SUPPORTED=List.of(
  new Capability("image/jpeg","JPEG",".jpg",true,true),new Capability("image/png","PNG",".png",true,true),
  new Capability("image/webp","WebP",".webp",true,true),new Capability("image/tiff","TIFF",".tif",true,true),
  new Capability("audio/wav","WAV audio",".wav",true,true),new Capability("audio/mpeg","MP3 audio",".mp3",true,true),
  new Capability("audio/flac","FLAC audio",".flac",true,true),new Capability("video/mp4","MP4 video",".mp4",true,true),
  new Capability("application/pdf","PDF document",".pdf",true,true));
 public static String extension(String mime){return SUPPORTED.stream().filter(c->c.mime().equals(mime)).findFirst().orElseThrow().extension();}
 private static boolean at(byte[] bytes,int offset,String value){byte[] expected=value.getBytes(StandardCharsets.ISO_8859_1);return bytes.length>=offset+expected.length && Arrays.equals(bytes,offset,offset+expected.length,expected,0,expected.length);}
 public static String detect(MultipartFile file)throws java.io.IOException{
  byte[] h;try(var stream=file.getInputStream()){h=stream.readNBytes(32);}return detect(h);
 }
 public static String detect(java.nio.file.Path file)throws java.io.IOException{try(var stream=java.nio.file.Files.newInputStream(file)){return detect(stream.readNBytes(32));}}
 private static String detect(byte[] h){
  if(h.length>=3 && (h[0]&255)==255 && (h[1]&255)==216 && (h[2]&255)==255)return "image/jpeg";
  if(at(h,0,"\u0089PNG\r\n\u001a\n"))return "image/png";
  if(at(h,0,"RIFF") && at(h,8,"WAVE"))return "audio/wav";
  if(at(h,0,"RIFF") && at(h,8,"WEBP"))return "image/webp";
  if(at(h,0,"II*\0") || at(h,0,"MM\0*"))return "image/tiff";
  if(at(h,0,"fLaC"))return "audio/flac";
  if(at(h,0,"ID3") || (h.length>=2 && (h[0]&255)==255 && (h[1]&224)==224 && (h[1]&6)!=0))return "audio/mpeg";
  if(at(h,0,"%PDF-"))return "application/pdf";
  if(at(h,4,"ftyp") && h.length>=12){String brand=new String(h,8,4,StandardCharsets.ISO_8859_1);if(Set.of("isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","avc1","dash","M4V ").contains(brand))return "video/mp4";}
  return null;
 }
}
