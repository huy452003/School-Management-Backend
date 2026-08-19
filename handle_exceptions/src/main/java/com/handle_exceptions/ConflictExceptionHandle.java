package com.handle_exceptions;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.util.List;

@Data
@AllArgsConstructor
@ToString
public class ConflictExceptionHandle extends RuntimeException {
    private List<String> conflictList;
    private final String modelName;

    public ConflictExceptionHandle(String message, List<String> conflictList, String modelName) {
        super(message);
        this.conflictList = conflictList;
        this.modelName = modelName;
    }
}
