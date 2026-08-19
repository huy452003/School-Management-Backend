package com.security.services;

import com.handle_exceptions.ConflictExceptionHandle;
import com.handle_exceptions.NotFoundExceptionHandle;
import com.logging.models.LogContext;
import com.logging.services.LoggingService;
import com.model_shared.enums.Status;
import com.model_shared.models.user.UpdateUserDto;
import com.model_shared.models.user.UserDto;
import com.security.entities.UserEntity;
import com.security.repositories.UserRepo;
import com.security.utils.UserDtoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthInternalService {

    @Autowired
    private UserRepo userRepo;
    @Autowired
    private UserDtoMapper userDtoMapper;
    @Autowired
    private LoggingService loggingService;
    @Autowired
    private IpBlockingService ipBlockingService;

    private LogContext ctx(String methodName) {
        return LogContext.builder()
                .module("security")
                .className(this.getClass().getSimpleName())
                .methodName(methodName)
                .build();
    }

    @Transactional(readOnly = true)
    public List<UserDto> batchGetUsersByIds(List<Integer> userIds) {
        LogContext logContext = ctx("batchGetUsersByIds");
        if (userIds == null || userIds.isEmpty()) {
            loggingService.logWarn("Empty userIds list in batch request", logContext);
            return List.of();
        }
        loggingService.logInfo("Batch getting users, count: " + userIds.size(), logContext);
        List<UserEntity> entities = userRepo.findAllById(userIds);
        List<UserDto> userDtos = entities.stream()
                .map(userDtoMapper::fromEntity)
                .collect(Collectors.toList());
        loggingService.logInfo("Successfully retrieved " + userDtos.size() + " users", logContext);
        return userDtos;
    }

    @Retryable(retryFor = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class)
    public UserDto updateUser(UpdateUserDto updateUserDto) {
        LogContext logContext = ctx("updateUser");
        loggingService.logInfo("updateUser internal — userId: " + updateUserDto.getUserId(), logContext);
        try {
            UserEntity userEntity = userRepo.findById(updateUserDto.getUserId()).orElseThrow(
                    () -> new NotFoundExceptionHandle("", List.of(updateUserDto.getUserId().toString()), "Security-Model")
            );

            if (userEntity.getStatus().equals(Status.DISABLED)) {
                loggingService.logWarn("Updating user with DISABLED status for userId: " + updateUserDto.getUserId()
                        + ". User is disabled but update is allowed.", logContext);
            }

            String normalizedPhone = updateUserDto.getPhoneNumber().trim().toLowerCase();
            String normalizedEmail = updateUserDto.getEmail().trim().toLowerCase();

            if (userRepo.existsByPhoneNumber(normalizedPhone)) {
                if (!userEntity.getPhoneNumber().equals(normalizedPhone)) {
                    throw new ConflictExceptionHandle(
                            "Phone number already exists", List.of(normalizedPhone), "Security-Model"
                    );
                }
            }
            if (userRepo.existsByEmail(normalizedEmail)) {
                if (!userEntity.getEmail().equals(normalizedEmail)) {
                    throw new ConflictExceptionHandle(
                            "Email already exists", List.of(normalizedEmail), "Security-Model"
                    );
                }
            }

            userEntity.setFirstName(updateUserDto.getFirstName());
            userEntity.setLastName(updateUserDto.getLastName());
            userEntity.setBirth(updateUserDto.getBirth());
            userEntity.setGender(updateUserDto.getGender());
            userEntity.setPhoneNumber(normalizedPhone);
            userEntity.setEmail(normalizedEmail);

            UserEntity savedEntity = userRepo.saveAndFlush(userEntity);
            loggingService.logInfo("Updated user profile for userId: " + updateUserDto.getUserId(), logContext);
            return userDtoMapper.fromEntity(savedEntity);
        } catch (Exception e) {
            loggingService.logError("Failed to update user for userId: " + updateUserDto.getUserId()
                    + ". Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e, logContext);
            throw e;
        }
    }

    @Transactional
    public List<Integer> deleteUsers(List<Integer> req) {
        LogContext logContext = ctx("deleteUsers");
        List<Integer> userIds = deduplicateUserIds(req, logContext);

        for (Integer userId : userIds) {
            UserEntity userEntity = userRepo.findById(userId).orElseThrow(
                    () -> new NotFoundExceptionHandle("", List.of(userId.toString()), "Security-Model")
            );
            userRepo.delete(userEntity);
            loggingService.logInfo("Deleted user with id: " + userId, logContext);
        }
        return userIds;
    }

    @Transactional
    public void disableUsers(List<Integer> req) {
        LogContext logContext = ctx("disableUsers");
        List<Integer> userIds = deduplicateUserIds(req, logContext);

        for (Integer userId : userIds) {
            UserEntity userEntity = userRepo.findById(userId).orElseThrow(
                    () -> new NotFoundExceptionHandle("", List.of(userId.toString()), "Security-Model")
            );
            if (userEntity.getStatus() == Status.DISABLED) {
                loggingService.logWarn("User with id: " + userId + " is already DISABLED. Skipping (idempotent operation).", logContext);
                continue;
            }
            userEntity.setStatus(Status.DISABLED);
            userRepo.save(userEntity);
            loggingService.logInfo("Disabled user with id: " + userId, logContext);
        }
    }

    @Transactional
    public void enableUsers(List<Integer> req) {
        LogContext logContext = ctx("enableUsers");
        List<Integer> userIds = deduplicateUserIds(req, logContext);

        for (Integer userId : userIds) {
            UserEntity userEntity = userRepo.findById(userId).orElseThrow(
                    () -> new NotFoundExceptionHandle("", List.of(userId.toString()), "Security-Model")
            );
            if (userEntity.getStatus() == Status.ENABLED) {
                loggingService.logWarn("User with id: " + userId + " is already ENABLED. Skipping (idempotent operation).", logContext);
                continue;
            }
            userEntity.setStatus(Status.ENABLED);
            userRepo.save(userEntity);
            loggingService.logInfo("Enabled user with id: " + userId, logContext);
        }
    }

    public Map<String, String> trackIpViolation(Map<String, String> request) {
        LogContext logContext = ctx("trackIpViolation");
        String ipAddress = request.get("ipAddress");
        if (ipAddress == null || ipAddress.isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "IP address is required");
            return error;
        }
        ipBlockingService.incrementBlockedCountAndAutoBlock(ipAddress);
        loggingService.logInfo("Tracked IP violation for IP: " + ipAddress, logContext);
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "IP violation tracked");
        response.put("ipAddress", ipAddress);
        return response;
    }

    private List<Integer> deduplicateUserIds(List<Integer> req, LogContext logContext) {
        Set<Integer> uniqueUserIds = new LinkedHashSet<>();
        List<Integer> duplicates = new ArrayList<>();
        for (Integer userId : req) {
            if (!uniqueUserIds.add(userId)) {
                duplicates.add(userId);
            }
        }
        if (!duplicates.isEmpty()) {
            loggingService.logWarn("Duplicate userIds detected in input: " + duplicates + ". They will be processed only once.", logContext);
        }
        return new ArrayList<>(uniqueUserIds);
    }
}
