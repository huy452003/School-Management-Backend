package com.handle_exceptions;

import lombok.Data;
import lombok.ToString;

import java.util.List;

@Data
@ToString
public class NotFoundExceptionHandle extends RuntimeException {
    private final List<String> notFounds;
    private final String modelName;
    public NotFoundExceptionHandle(String message, List<String> notFounds, String modelName) {
        super(message);
        this.notFounds = notFounds;
        this.modelName = modelName;
    }
} 