package com.security_shared.services;

import com.model_shared.models.Response;
import com.model_shared.models.user.UserDto;
import com.model_shared.models.user.UpdateUserDto;
import com.model_shared.enums.Role;
import com.model_shared.enums.Permission;
import com.handle_exceptions.UnauthorizedExceptionHandle;
import com.handle_exceptions.ForbiddenExceptionHandle;
import com.handle_exceptions.ServiceUnavailableExceptionHandle;
import com.handle_exceptions.NotFoundExceptionHandle;
import com.logging.models.LogContext;
import com.logging.services.LoggingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Service
public class SecurityService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private LoggingService loggingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${security.base-url:http://localhost:8083}")
    private String securityBaseUrl;

    private LogContext getLogContext(String methodName) {
        return LogContext.builder()
                .module("security_shared")
                .className(this.getClass().getSimpleName())
                .methodName(methodName)
                .build();
    }

    public UserDto validateAndAuthorize(String authHeader, String[] requiredRoles, String[] requiredPermissions) {
        LogContext logContext = getLogContext("validateAndAuthorize");
        
        // Kiểm tra Authorization header
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            loggingService.logDebug("Missing or invalid Authorization header", logContext);
            throw new UnauthorizedExceptionHandle(
                    "Missing or invalid Authorization header",
                    "Authorization header must start with 'Bearer '");
        }

        String token = authHeader.substring(7);
        
        // Validate token và lấy user
        UserDto user = validateTokenAndGetUser(token);
        if (user == null) {
            loggingService.logDebug("Token validation failed - invalid, expired, or blacklisted", logContext);
            throw new UnauthorizedExceptionHandle(
                "Token validation failed", 
                "Token may be invalid, expired, or blacklisted");
        }

        boolean isAdmin = Role.ADMIN.equals(user.getRole());

        // Kiểm tra roles
        if (!isAdmin && !hasRequiredRole(user, requiredRoles)) {
            String requiredRolesStr = String.join(", ", requiredRoles);
            loggingService.logDebug("User role " + user.getRole() 
                    + " does not match required roles: " + requiredRolesStr, logContext);
            throw new ForbiddenExceptionHandle(
                "Insufficient role privileges", 
                "User does not have required role: " + requiredRolesStr);
        }

        // Kiểm tra permissions
        if (!isAdmin && !hasRequiredPermission(user, requiredPermissions)) {
            String requiredPermissionsStr = String.join(", ", requiredPermissions);
            loggingService.logDebug("User permissions " + user.getPermissions() 
                    + " do not include required permissions: " + requiredPermissionsStr, logContext);
            throw new ForbiddenExceptionHandle(
                "Insufficient permission privileges", 
                "User does not have required permissions: " + requiredPermissionsStr);
        }

        loggingService.logDebug("User " + user.getUsername() + " successfully authorized", logContext);
        return user;
    }

    @CircuitBreaker(name = "security-service", fallbackMethod = "validateTokenAndGetUserFallback")
    public UserDto validateTokenAndGetUser(String token) {
        LogContext logContext = getLogContext("validateTokenAndGetUser");
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            
            HttpEntity<String> entity = new HttpEntity<>(headers);

            loggingService.logDebug("Calling security module with URL: " + securityBaseUrl + "/auth/validate" 
                    + " to validate token", logContext);

            ResponseEntity<Response<UserDto>> response = restTemplate.exchange(
                securityBaseUrl + "/auth/validate",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Response<UserDto> responseBody = response.getBody();
                if (responseBody != null && responseBody.data() != null) {
                    UserDto user = responseBody.data();

                    String cacheKey = "user:token:" + token;
                    redisTemplate.opsForValue().set(cacheKey, user, Duration.ofMinutes(5));

                    loggingService.logDebug(String.format("Successfully retrieved user from security module: %s and cached in Redis"
                        + " with key: %s", user.getUsername(), cacheKey), logContext);
                    return user;
                }
            }
            
            loggingService.logWarn("Failed to get valid response from security module. Status: " 
                    + response.getStatusCode() + " Body: " + response.getBody(), logContext);
            return null;
            
        } catch (Exception e) {
            loggingService.logError("Exception during token validation: ", e, logContext);
            return null;
        }
    }

    public UserDto validateTokenAndGetUserFallback(String token, Exception e) {
        LogContext logContext = getLogContext("validateTokenAndGetUserFallback");

        String cacheKey = "user:token:" + token;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            loggingService.logInfo("Using cached user data due to service unavailability: " 
                + " with key: " + cacheKey, logContext);
            @SuppressWarnings("unchecked")
            UserDto cachedUser = (UserDto) cached;
            return cachedUser;
        }
        loggingService.logError("No cached user data found, throwing ServiceUnavailableException", e, logContext);
        throw new ServiceUnavailableExceptionHandle(
            "Security service is currently unavailable",
            "Please try again later"
        );
    }

    public boolean hasRequiredRole(UserDto user, String[] requiredRoles) {
        if (requiredRoles == null || requiredRoles.length == 0) {
            return true;
        }
        
        Role userRole = user.getRole();
        for (String requiredRole : requiredRoles) {
            if (Role.ADMIN.equals(userRole) || requiredRole.equals(userRole.name())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasRequiredPermission(UserDto user, String[] requiredPermissions) {
        if (requiredPermissions == null || requiredPermissions.length == 0) {
            return true;
        }
        
        // ADMIN có tất cả permissions
        if (Role.ADMIN.equals(user.getRole())) {
            return true;
        }
        
        Set<Permission> userPermissions = user.getPermissions();
        if (userPermissions == null) {
            return false;
        }
        
        // Convert String sang Permission enum để so sánh
        for (String requiredPermission : requiredPermissions) {
            try {
                Permission permissionEnum = Permission.valueOf(requiredPermission);
                if (userPermissions.contains(permissionEnum)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Invalid permission string, skip
                loggingService.logWarn("Invalid permission string: " + requiredPermission, getLogContext("hasRequiredPermission"));
            }
        }
        return false;
    }

    @CircuitBreaker(name = "security-service", fallbackMethod = "getUsersByIdsFallback")
    public Map<Integer, UserDto> getUsersByIds(List<Integer> userIds) {
        LogContext logContext = getLogContext("getUsersByIds");
        
        if (userIds == null || userIds.isEmpty()) {
            loggingService.logDebug("Empty userIds list provided", logContext);
            return new HashMap<>();
        }

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ids", userIds);

            loggingService.logDebug("Calling security internal API to batch get users, count: " + userIds.size(), logContext);

            @SuppressWarnings("unchecked")
            List<Object> dataFromUsers = restTemplate.postForObject(
                    securityBaseUrl + "/auth/internal/users/batch",
                    requestBody,
                    List.class
            );

            Map<Integer, UserDto> usersById = new HashMap<>();
            if (dataFromUsers != null) {
                for (Object obj : dataFromUsers) {
                    // Convert LinkedHashMap sang UserDto
                    UserDto user = objectMapper.convertValue(obj, UserDto.class);
                    usersById.put(user.getUserId(), user);
                }
                loggingService.logDebug("Successfully retrieved " + usersById.size() + " users", logContext);
            }

            return usersById;
        } catch (Exception e) {
            loggingService.logError("Failed to get users from Security service", e, logContext);
            throw new ServiceUnavailableExceptionHandle(
                "Cannot retrieve user information from Security service",
                "Security service may be down or experiencing issues. Please try again later.",
                "Security"
            );
        }
    }

    @CircuitBreaker(name = "security-service", fallbackMethod = "updateUserFallback")
    public UserDto updateUser(UpdateUserDto updateUserDto) {
        LogContext logContext = getLogContext("updateUser");
        
        if (updateUserDto == null || updateUserDto.getUserId() == null) {
            loggingService.logWarn("Invalid user data provided for update", logContext);
            throw new IllegalArgumentException("UpdateUserDto and userId must not be null");
        }
        
        try {
            loggingService.logDebug("Calling security internal API to update user: " + updateUserDto.getUserId(), logContext);

            ResponseEntity<UserDto> response = restTemplate.postForEntity(
                    securityBaseUrl + "/auth/internal/users/update",
                    updateUserDto,
                    UserDto.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                loggingService.logDebug("Successfully updated user: " + updateUserDto.getUserId(), logContext);
                return response.getBody();
            }

            if(response.getStatusCode().value() == 404) {
                loggingService.logWarn("User not found. Status: " + response.getStatusCode(), logContext);
                throw new NotFoundExceptionHandle("", List.of(updateUserDto.getUserId().toString()), "Security-Model");
            }

            loggingService.logWarn("Failed to update user. Status: " + response.getStatusCode(), logContext);
            throw new ServiceUnavailableExceptionHandle(
                "Failed to update user in Security service",
                "Security service returned non-success status",
                "Security-Model"
            );
        } catch (Exception e) {
            loggingService.logError("Failed to update user in Security service", e, logContext);
            throw new ServiceUnavailableExceptionHandle(
                "Cannot update user information in Security service",
                "Security service may be down or experiencing issues. Please try again later.",
                "Security-Model"
            );
        }
    }

    @CircuitBreaker(name = "security-service", fallbackMethod = "deleteUsersFallback")
    public List<Integer> deleteUsers(List<Integer> userIds) {
        LogContext logContext = getLogContext("deleteUsers");
        
        if (userIds == null || userIds.isEmpty()) {
            loggingService.logWarn("Empty userIds list provided", logContext);
            return new ArrayList<>();
        }

        try {
            loggingService.logDebug("Calling security internal API to delete users: " + userIds, logContext);

            ResponseEntity<List<Integer>> response = restTemplate.exchange(
                    securityBaseUrl + "/auth/internal/users/delete",
                    HttpMethod.DELETE,
                    new HttpEntity<>(userIds),
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                loggingService.logDebug("Successfully deleted users: " + userIds, logContext);
                return response.getBody();
            }

            if (response.getStatusCode().value() == 404) {
                loggingService.logWarn("Users not found. Status: " + response.getStatusCode(), logContext);
                throw new NotFoundExceptionHandle("", userIds.stream().map(String::valueOf).toList(), "Security-Model");
            }

            loggingService.logWarn("Failed to delete users. Status: " + response.getStatusCode(), logContext);
            throw new ServiceUnavailableExceptionHandle(
                "Failed to delete users in Security service",
                "Security service returned non-success status: " + response.getStatusCode(),
                "Security-Model"
            );
            
        } catch (NotFoundExceptionHandle e) {
            throw e;
        } catch (Exception e) {
            loggingService.logError("Failed to delete users in Security service", e, logContext);
            throw new ServiceUnavailableExceptionHandle(
                "Cannot delete users in Security service",
                "Security service may be down or experiencing issues. Please try again later.",
                "Security-Model"
            );
        }
    }

    public Map<Integer, UserDto> getUsersByIdsFallback(List<Integer> userIds, Exception e) {
        LogContext logContext = getLogContext("getUsersByIdsFallback");
        loggingService.logError("Exception during batch get users: ", e, logContext);
        throw new ServiceUnavailableExceptionHandle(
            "Cannot retrieve user information from Security service",
            "Security service may be down or experiencing issues. Please try again later.",
            "Security-Model"
        );
    }

    public UserDto updateUserFallback(UpdateUserDto updateUserDto, Exception e) {
        LogContext logContext = getLogContext("updateUserFallback");
        loggingService.logError("Exception during update user: ", e, logContext);
        throw new ServiceUnavailableExceptionHandle(
            "Cannot update user information in Security service",
            "Security service may be down or experiencing issues. Please try again later.",
            "Security-Model"
        );
    }

    public List<Integer> deleteUsersFallback(List<Integer> userIds, Exception e) {
        LogContext logContext = getLogContext("deleteUsersFallback");
        loggingService.logError("Exception during delete users: ", e, logContext);
        throw new ServiceUnavailableExceptionHandle(
            "Cannot delete users in Security service",
            "Security service may be down or experiencing issues. Please try again later.",
            "Security-Model"
        );
    }
    
    @CircuitBreaker(name = "security-service", fallbackMethod = "sendIpViolationFallback")
    public void sendIpViolation(String ipAddress) {
        LogContext logContext = getLogContext("sendIpViolation");
        
        if (ipAddress == null || ipAddress.isEmpty()) {
            loggingService.logWarn("Invalid IP address provided for sending", logContext);
            return;
        }
        
        try {
            loggingService.logDebug("Calling security internal API to send IP violation: " + ipAddress, logContext);
            
            // Tạo request body với Map để đảm bảo Content-Type là application/json
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("ipAddress", ipAddress);
            
            // Set Content-Type header
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                securityBaseUrl + "/auth/internal/ip/track-violation",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, String>>() {}
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                loggingService.logDebug("Successfully sent IP violation for: " + ipAddress, logContext);
            } else {
                loggingService.logWarn("Failed to send IP violation. Status: " + response.getStatusCode(), logContext);
            }
        } catch (Exception e) {
            loggingService.logError("Failed to send IP violation in Security service", e, logContext);
        }
    }
    
    public void sendIpViolationFallback(String ipAddress, Exception e) {
        LogContext logContext = getLogContext("sendIpViolationFallback");
        loggingService.logError("Exception during send IP violation: " + ipAddress, e, logContext);
        throw new ServiceUnavailableExceptionHandle(
            "Cannot send IP violation in Security service",
            "Security service may be down or experiencing issues. Please try again later.",
            "Security-Model"
        );
    }
}
