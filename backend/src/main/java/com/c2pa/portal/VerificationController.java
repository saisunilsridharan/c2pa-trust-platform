package com.c2pa.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/verification")
@SecurityRequirement(name="adminToken")
public class VerificationController {
 private final ConfigurationRepository repository;
 private final ObjectMapper mapper;
 public VerificationController(ConfigurationRepository repository,ObjectMapper mapper) {
  this.repository=repository; this.mapper=mapper;
 }
 @PostMapping(consumes="multipart/form-data")
 public JsonNode inspect(@RequestPart("file") MultipartFile file) throws Exception {
  var record=repository.findById(1L).filter(r->r.active!=null)
   .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Activate a profile first"));
  var settings=mapper.readValue(record.active,ConfigurationController.Settings.class);
  if(file.isEmpty() || file.getSize()>settings.maxUploadMb()*1024L*1024)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File exceeds profile limits or is empty");
  byte[] header;
  try(var stream=file.getInputStream()){header=stream.readNBytes(8);}
  String format=header.length>=3 && (header[0]&255)==255 && (header[1]&255)==216 && (header[2]&255)==255?"image/jpeg":
   java.util.Arrays.equals(header,new byte[]{(byte)137,80,78,71,13,10,26,10})?"image/png":null;
  if(format==null || !settings.formats().contains(format))
   throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Unsupported or disabled format");
  Path worker=Path.of("../c2pa-worker/target/debug/c2pa-worker").toAbsolutePath().normalize();
  if(!Files.isExecutable(worker)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Build the Rust worker first");
  Path directory=Files.createTempDirectory("c2pa-inspection-");
  Process process=null;
  try {
   Path input=directory.resolve(format.equals("image/png")?"asset.png":"asset.jpg");
   file.transferTo(input);
   Path output=directory.resolve("report.json");
   process=new ProcessBuilder(worker.toString(),input.toString())
    .redirectOutput(output.toFile()).redirectError(directory.resolve("error.log").toFile()).start();
   if(!process.waitFor(30,TimeUnit.SECONDS)) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"Inspection timed out");
   if(process.exitValue()!=0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"No readable C2PA manifest, or malformed content");
   if(Files.size(output)>8*1024*1024) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Manifest report exceeds limits");
   return mapper.readTree(output.toFile());
  } finally {
   if(process!=null && process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
   try(var files=Files.list(directory)){for(Path path:files.toList())Files.deleteIfExists(path);}
   Files.deleteIfExists(directory);
  }
 }
}
