package io.servicecomb.poc.demo.seckill;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<String> handle(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode()).body(exception.getReason());
  }
}
