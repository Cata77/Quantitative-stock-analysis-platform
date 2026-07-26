package com.quantplatform.screener.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.quantplatform.screener.search.SearchUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        var detail = createProblem(
                HttpStatus.BAD_REQUEST, "Request validation failed", request);
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            var parameter = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    violations.putIfAbsent(parameter, error.getDefaultMessage()));
        });
        detail.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Query parameter is invalid", request);
    }

    @ExceptionHandler({SearchUnavailableException.class, DataAccessException.class})
    ResponseEntity<ProblemDetail> handleDependencyUnavailable(HttpServletRequest request) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Screener data is temporarily unavailable",
                request);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(createProblem(status, message, request));
    }

    private ProblemDetail createProblem(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        var detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setInstance(URI.create(request.getRequestURI()));
        return detail;
    }
}
