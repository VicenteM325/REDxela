package com.redxela.api;

import com.redxela.auth.EmailAlreadyRegisteredException;
import com.redxela.auth.InvalidCredentialsException;
import com.redxela.reception.ReceptionOverloadedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;
import java.util.stream.Collectors;

/** Traduce las excepciones del sistema a respuestas HTTP con un cuerpo JSON uniforme. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<Map<String, String>> conflict(EmailAlreadyRegisteredException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({InvalidCredentialsException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, String>> unauthorized(Exception e) {
        String msg = e instanceof InvalidCredentialsException ? e.getMessage() : "Falta el encabezado Authorization";
        return error(HttpStatus.UNAUTHORIZED, msg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Inventario o el escáner no dieron abasto: el lote no se aceptó, pero tampoco se perdió. */
    @ExceptionHandler({ReceptionOverloadedException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> overloaded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return error(HttpStatus.SERVICE_UNAVAILABLE, e instanceof ReceptionOverloadedException
                ? e.getMessage() : "Operación interrumpida");
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflictState(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** JSON malformado, parámetros fuera de rango o de tipo incorrecto: error del cliente, no del servidor. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "Solicitud inválida");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "Recurso inexistente");
    }

    /** Red de seguridad: se registra el detalle y no se filtra al cliente. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Error inesperado", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
