package com.campusbuddies.common;

import org.springframework.http.HttpStatus;

/** 业务异常：携带 HTTP 状态码和面向用户的中文提示。 */
public class ApiException extends RuntimeException {
  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException unauthorized(String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, message);
  }

  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "无权操作");
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, message);
  }

  public static ApiException missing(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  public static ApiException tooMany(String message) {
    return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
  }
}
