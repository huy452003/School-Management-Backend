package com.handle_exceptions;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
public class TooManyRequestsExceptionHandle extends RuntimeException {
    private String message;
    private String details;
    private String modelName;
    private Long retryAfterSeconds; // Thời gian cần chờ trước khi retry (seconds)
    
    public TooManyRequestsExceptionHandle(String message) {
        this.message = message;
    }
    
    public TooManyRequestsExceptionHandle(String message, Long retryAfterSeconds) {
        this.message = message;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    
    public TooManyRequestsExceptionHandle(String message, String modelName, Long retryAfterSeconds) {
        this.message = message;
        this.modelName = modelName;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}

