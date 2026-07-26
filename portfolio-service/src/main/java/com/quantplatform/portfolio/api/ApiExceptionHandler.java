package com.quantplatform.portfolio.api;

import com.quantplatform.portfolio.service.HoldingNotFoundException;
import com.quantplatform.portfolio.service.InvalidAuthenticatedUserException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidAuthenticatedUserException.class)
    ResponseEntity<ProblemDetail> handleUnauthorized(
            InvalidAuthenticatedUserException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.UNAUTHORIZED, exception.getMessage(), request);
    }

    @ExceptionHandler(HoldingNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(
            HoldingNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        ProblemDetail detail = createProblem(
                HttpStatus.BAD_REQUEST, "Request validation failed", request);
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                violations.putIfAbsent(error.getField(), error.getDefaultMessage()));
        detail.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Path parameter is invalid", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleMalformedBody(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Request body is malformed", request);
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
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setInstance(URI.create(request.getRequestURI()));
        return detail;
    }
}
