package com.campusbuddies.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一把异常转换成 {"error": "..."}，前端只需读取 error 字段。 */
@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, String>> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
    String message = e.getMessage() == null ? "请求参数不正确" : e.getMessage();
    return ResponseEntity.badRequest().body(Map.of("error", message));
  }
}
