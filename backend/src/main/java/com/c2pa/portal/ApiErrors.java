package com.c2pa.portal;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class ApiErrors {
 @ExceptionHandler(OptimisticLockingFailureException.class)
 public ResponseEntity<Map<String,String>> conflict() {
  return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Configuration changed; reload before saving"));
 }
}
