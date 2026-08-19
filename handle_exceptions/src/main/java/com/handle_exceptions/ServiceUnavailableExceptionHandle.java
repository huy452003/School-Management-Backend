package com.handle_exceptions;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
public class ServiceUnavailableExceptionHandle extends RuntimeException {
    private String message;
    private String details;
    private String modelName;
    
    public ServiceUnavailableExceptionHandle(String message) {
        this.message = message;
    }
    
    public ServiceUnavailableExceptionHandle(String message, String modelName) {
        this.message = message;
        this.modelName = modelName;
    }
}

