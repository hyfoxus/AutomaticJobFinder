package com.gnemirko.jobapplier.scoring.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.exception.BadRequestException;

/** Bad input becomes a short JSON 400 with no stack trace. */
@RestControllerAdvice
@ScoringEnabled
public class ApiErrorAdvice {

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
        return json(e.getMessage());
    }

    @ExceptionHandler(com.gnemirko.jobapplier.scoring.exception.LearnForbiddenException.class)
    ResponseEntity<Map<String, String>> learnForbidden(Exception e) {
        return json(HttpStatus.FORBIDDEN, "forbidden");
    }

    @ExceptionHandler(com.gnemirko.jobapplier.scoring.exception.PayloadTooLargeException.class)
    ResponseEntity<Map<String, String>> tooLarge(Exception e) {
        return json(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(Exception e) {
        return json("invalid request body");
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> binding(Exception e) {
        return json("invalid request");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> methodNotAllowed(Exception e) {
        return json(HttpStatus.METHOD_NOT_ALLOWED, "method not allowed");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Map<String, String>> notAcceptable(Exception e) {
        return json(HttpStatus.NOT_ACCEPTABLE, "only application/json is available");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> notFound(Exception e) {
        return json(HttpStatus.NOT_FOUND, "not found");
    }

    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    ResponseEntity<Map<String, String>> unavailable(Exception e) {
        return json(HttpStatus.SERVICE_UNAVAILABLE, "service is shutting down");
    }

    private static ResponseEntity<Map<String, String>> json(String message) {
        return json(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseEntity<Map<String, String>> json(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
    }
}
