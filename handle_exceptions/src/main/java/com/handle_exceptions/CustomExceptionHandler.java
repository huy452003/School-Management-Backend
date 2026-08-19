package com.handle_exceptions;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.model_shared.models.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.*;

@RestControllerAdvice
public class CustomExceptionHandler {
    @Autowired
    MessageSource messageSource;
    
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NotFoundExceptionHandle.class)
    ResponseEntity<Response<?>> notFoundExceptionHandler(NotFoundExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", "IDs:" + e.getNotFounds().toString());
        
        Response<?> response = new Response<>(
                404,
                messageSource.getMessage("response.error.notFoundError", null, locale),
                e.getModelName(),
                error,
                null
        );
        return ResponseEntity.status(404).body(response);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ConflictExceptionHandle.class)
    ResponseEntity<Response<?>> conflictExceptionHandler(ConflictExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        String detail = buildConflictDetail(e);
        error.put("Error", detail);

        Response<?> response = new Response<>(
                409,
                messageSource.getMessage("response.error.conflict", null, locale),
                e.getModelName(),
                error,
                null
        );
        return ResponseEntity.status(409).body(response);
    }

    private static String buildConflictDetail(ConflictExceptionHandle e) {
        String msg = e.getMessage();
        List<String> list = e.getConflictList();
        String values = (list == null || list.isEmpty()) ? "" : String.join(", ", list);
        if (msg != null && !msg.isBlank()) {
            return values.isEmpty() ? msg : msg + ": " + values;
        }
        return values.isEmpty() ? "Conflict" : values;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<?>> handleBodyValidation(MethodArgumentNotValidException ex) {
        Locale locale = LocaleContextHolder.getLocale();

        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> {
            String key = fe.getDefaultMessage();
            if (key != null && key.startsWith("{") && key.endsWith("}")) {
                key = key.substring(1, key.length() - 1);
            }
            String msg = messageSource.getMessage(
                    key != null ? key : fe.getField(), null, key != null ? key : fe.getField(), locale
            );
            
            // Extract field name without nested object prefix (e.g., "user.firstName" -> "firstName")
            String fieldName = fe.getField();
            if (fieldName.contains(".")) {
                fieldName = fieldName.substring(fieldName.lastIndexOf(".") + 1);
            }
            
            errors.put(fieldName, msg);
        });

        Response<?> resp = new Response<>(
                400,
                messageSource.getMessage("response.error.validateFailed", null, locale),
                null,
                errors,
                null
        );
        return ResponseEntity.badRequest().body(resp);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ValidationExceptionHandle.class)
    ResponseEntity<Response<?>> validationExceptionHandler(ValidationExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        if (e.getDetails() != null) {
            error.put("Details", e.getDetails());
        }
        if (e.getInvalidFields() != null && !e.getInvalidFields().isEmpty()) {
            error.put("InvalidFields", e.getInvalidFields().toString());
        }
        
        Response<?> response = new Response<>(
                400,
                messageSource.getMessage("response.error.validateFailed", null, locale),
                e.getModelName() != null ? e.getModelName() : "Request-Model",
                error,
                null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<?>> handleInvalidFormat(HttpMessageNotReadableException ex) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> errors = new HashMap<>();
        Throwable cause = ex.getMostSpecificCause();

        if (cause instanceof InvalidFormatException || cause instanceof MismatchedInputException) {
            List<JsonMappingException.Reference> path = ((JsonMappingException) cause).getPath();
            if (!path.isEmpty()) {
                String field = path.get(path.size() - 1).getFieldName();
                
                // Try validate.user.{field}.invalidType first (for user-related fields)
                String key = String.format("validate.user.%s.invalidType", field);
                String msg = messageSource.getMessage(key, null, locale);
                
                // If message equals key (not found), try validate.{field}.invalidType
                if (msg.equals(key)) {
                    key = String.format("validate.%s.invalidType", field);
                    msg = messageSource.getMessage(key, null, locale);
                }
                
                // If still not found (msg equals key), use default message based on locale
                if (msg.equals(key)) {
                    msg = locale.getLanguage().equals("vi") 
                            ? field + " không đúng định dạng." 
                            : field + " is invalid format.";
                }
                
                errors.put(field, msg);
            }
        } else {
            String errorMsg = messageSource.getMessage("response.error.validateFailed", null, locale);
            errors.put("error", errorMsg);

        }

        Response<?> response = new Response<>(
                400,
                messageSource.getMessage("response.error.validateFailed", null, locale),
                "Request-Model",
                errors,
                null
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(UnauthorizedExceptionHandle.class)
    ResponseEntity<Response<?>> unauthorizedExceptionHandler(UnauthorizedExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        if (e.getDetails() != null) {
            error.put("Details", e.getDetails());
        }
      
        Response<?> response = new Response<>(
                401,
                messageSource.getMessage("response.error.unauthorized", null, locale),
                "Security",
                error,
                null
        );
            return ResponseEntity.status(401).body(response);
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(ForbiddenExceptionHandle.class)
    ResponseEntity<Response<?>> forbiddenExceptionHandler(ForbiddenExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        if (e.getRequiredRoles() != null) {
            error.put("RequiredRoles", e.getRequiredRoles());
        }
        
        Response<?> response = new Response<>(
                403,
                messageSource.getMessage("response.error.forbidden", null, locale),
                "Security",
                error,
                null
        );
        return ResponseEntity.status(403).body(response);
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(IpBlockedExceptionHandle.class)
    ResponseEntity<Response<?>> ipBlockedExceptionHandler(IpBlockedExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        if (e.getDetails() != null) {
            error.put("Details", e.getDetails());
        }
        if (e.getIpAddress() != null) {
            error.put("IpAddress", e.getIpAddress());
        }
        if (e.getReason() != null) {
            error.put("Reason", e.getReason());
        }
        
        Response<?> response = new Response<>(
                403,
                messageSource.getMessage("response.error.ipBlocked", null, locale),
                "Security",
                error,
                null
        );
        return ResponseEntity.status(403).body(response);
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(ServiceUnavailableExceptionHandle.class)
    ResponseEntity<Response<?>> serviceUnavailableExceptionHandler(ServiceUnavailableExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        if (e.getModelName() != null) {
            error.put("ModelName", e.getModelName());
        }
        if (e.getDetails() != null) {
            error.put("Details", e.getDetails());
        }
        
        Response<?> response = new Response<>(
                503,
                messageSource.getMessage("response.error.serviceUnavailable", null, locale),
                e.getModelName(),
                error,
                null
        );
        return ResponseEntity.status(503).body(response);
    }

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    @ExceptionHandler(TooManyRequestsExceptionHandle.class)
    ResponseEntity<Response<?>> tooManyRequestsExceptionHandler(TooManyRequestsExceptionHandle e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        if (e.getModelName() != null) {
            error.put("ModelName", e.getModelName());
        }
        if (e.getDetails() != null) {
            error.put("Details", e.getDetails());
        }
        if (e.getRetryAfterSeconds() != null) {
            error.put("RetryAfterSeconds", String.valueOf(e.getRetryAfterSeconds()));
        }
        
        Response<?> response = new Response<>(
                429,
                messageSource.getMessage("response.error.tooManyRequests", null, locale),
                e.getModelName() != null ? e.getModelName() : "RateLimit",
                error,
                null
        );
        
        // Add Retry-After header
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(429);
        if (e.getRetryAfterSeconds() != null) {
            responseBuilder.header("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        }
        
        return responseBuilder.body(response);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    ResponseEntity<Response<?>> exceptionHandler(Exception e) {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> error = new HashMap<>();
        error.put("Error", e.getMessage());
        
        Response<?> response = new Response<>(
                500,
                messageSource.getMessage("response.error.internalServerError", null, locale),
                "Exception",
                error,
                null
        );
        return ResponseEntity.status(500).body(response);
    }
} 