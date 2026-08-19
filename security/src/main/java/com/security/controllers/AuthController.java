package com.security.controllers;

import com.model_shared.models.Response;
import com.model_shared.models.user.UserDto;
import com.handle_exceptions.NotFoundExceptionHandle;
import com.logging.models.LogContext;
import com.logging.services.LoggingService;
import com.security.entities.UserEntity;
import com.security.models.Login;
import com.security.models.Register;
import com.security.models.SecurityResponse;
import com.security.models.TokenInfo;
import com.security.services.AuthService;
import com.security.services.IpBlockingService;
import com.model_shared.models.user.UpdateUserDto;
import com.model_shared.models.user.AdminUpdateUserDto;
import com.handle_exceptions.ValidationExceptionHandle;
import com.security.utils.SecurityUtils;
import com.security.utils.UserDtoMapper;
import com.security.services.AsyncService;
import com.security.services.AuthInternalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;


import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.security.repositories.UserRepo;

@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {
    @Autowired
    private AuthService authService;

    @Autowired
    private LoggingService loggingService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private ReloadableResourceBundleMessageSource messageSource;
    
    @Autowired
    private UserDtoMapper userDtoMapper;

    @Autowired
    private AuthInternalService authInternalService;
    
    @Autowired
    private AsyncService asyncService;
    
    @Autowired
    private SecurityUtils securityUtils;

    private LogContext getLogContext(String methodName) {
        return LogContext.builder()
                .module("security")
                .className(this.getClass().getSimpleName())
                .methodName(methodName)
                .build();
    }

    @PostMapping("/register")
    public ResponseEntity<Response<SecurityResponse>> register(
            @Valid @RequestBody Register request,
            @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);

        LogContext logContext = getLogContext("register");
        logContext.setUserId(request.getUsername());

        loggingService.logInfo("Register API Calling... by user: " + logContext.getUserId(), logContext);
        
        SecurityResponse securityResponse = authService.register(request);
        Response<SecurityResponse> response = new Response<>(
                200,
                messageSource.getMessage("response.message.registerSuccess", null, locale),
                "Security-Model",
                null,
                securityResponse
        );
        loggingService.logInfo("User registered successfully: " + logContext.getUserId(), logContext);
        return ResponseEntity.status(response.status()).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<Response<SecurityResponse>> login(
            @Valid @RequestBody Login request,
            @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("login");
        logContext.setUserId(request.username());

        loggingService.logInfo("Login API calling... by user: " + logContext.getUserId(), logContext);
        SecurityResponse securityResponse = authService.login(request);
        
        asyncService.sendLoginEvent(securityResponse);
        
        Response<SecurityResponse> response = new Response<>(
                200,
                messageSource.getMessage("response.message.loginSuccess",null,locale),
                "Security-Model",
                null,
                securityResponse
        );
        loggingService.logInfo("User logged in successfully: " + logContext.getUserId(), logContext);
        return ResponseEntity.status(response.status()).body(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Response<Map<String, Object>>> logout(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("logout");

        loggingService.logInfo("Logout API calling...", logContext);
        
        String token = authHeader.substring(7);
        Map<String, Object> logoutResponse = authService.logout(token);

        Response<Map<String, Object>> response = new Response<>(
                200,
                messageSource.getMessage("response.message.logoutSuccess", null, locale),
                "Security-Model",
                null,
                logoutResponse
        );

        loggingService.logInfo("User logged out from all devices successfully: " + logoutResponse.get("username")
        + " at timestamp: " + logoutResponse.get("timestamp"), logContext);
        return ResponseEntity.status(response.status()).body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Response<SecurityResponse>> refreshToken(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);

        LogContext logContext = getLogContext("refreshToken");

        loggingService.logInfo("refreshToken API Calling...", logContext);
        SecurityResponse securityResponse = authService.refreshToken(authHeader);
        Response<SecurityResponse> response = new Response<>(
                200,
                messageSource.getMessage("response.message.refreshTokenSuccess",null,locale),
                "Security-Model",
                null,
                securityResponse
        );
        loggingService.logInfo("refreshToken successfully", logContext);
        return ResponseEntity.status(response.status()).body(response);
    }

    @GetMapping("/validate")
    public ResponseEntity<Response<UserDto>> validateToken(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);

        LogContext logContext = getLogContext("validate");

        loggingService.logInfo("validate API Calling...", logContext);
        String token = authHeader.substring(7); 

            String username = authService.getUsernameFromToken(token);
            UserEntity user = userRepo.findByUsername(username).orElseThrow(
                    () -> new NotFoundExceptionHandle("", List.of(username), "Security-Model")
            );

            // Convert using ModelMapper
            UserDto userDto = userDtoMapper.fromEntity(user);

            Response<UserDto> response = new Response<>(
                    200,
                    messageSource.getMessage("response.message.validateSuccess",null,locale),
                    "Security-Model",
                    null,
                    userDto
            );

            loggingService.logInfo("Token validated successfully for user: " + username, logContext);
            return ResponseEntity.status(response.status()).body(response);
    }

    @GetMapping("/decode")
    public ResponseEntity<Response<TokenInfo>> decodeToken(
        @RequestHeader("Authorization") String authHeader,
        @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("decodeToken");

        loggingService.logInfo("decodeToken API Calling...", logContext);
        String token = authHeader.substring(7);

        TokenInfo tokenInfo = authService.decodeToken(token);
        Response<TokenInfo> response = new Response<>(
                200,
                messageSource.getMessage("response.message.decodeTokenSuccess", null, locale),
                "Security-Model",
                null,
                tokenInfo
        );

        loggingService.logInfo("Token decoded successfully", logContext);
        return ResponseEntity.status(response.status()).body(response);
    }

    // Internal API

    @PostMapping("/internal/users/batch")
    public ResponseEntity<List<UserDto>> getUsersByIds(
            @RequestBody Map<String, List<Integer>> request
    ) {
        LogContext logContext = getLogContext("getUsersByIds");
        loggingService.logInfo("getUsersByIds API Calling...", logContext);
        return ResponseEntity.ok(authInternalService.batchGetUsersByIds(request.get("ids")));
    }

    @PostMapping("/internal/users/update")
    public ResponseEntity<UserDto> updateUser(
        @Valid @RequestBody UpdateUserDto updateUserDto
    ) {
        LogContext logContext = getLogContext("updateUser");
        loggingService.logInfo("updateUser API Calling... for userId: " + updateUserDto.getUserId(), logContext);
        return ResponseEntity.ok(authInternalService.updateUser(updateUserDto));
    }

    @DeleteMapping("/internal/users/delete")
    public ResponseEntity<List<Integer>> deleteUser(@RequestBody List<Integer> req) {
        LogContext logContext = getLogContext("deleteUser");
        loggingService.logInfo("deleteUser API Calling...", logContext);
        return ResponseEntity.ok(authInternalService.deleteUsers(req));
    }

    @PatchMapping("/internal/users/disable")
    public ResponseEntity<Response<List<Integer>>> disableUsers(
        @RequestBody List<Integer> req,
        @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ) {
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("disableUsers");
        loggingService.logInfo("disableUsers API Calling...", logContext);
        authInternalService.disableUsers(req);
        Response<List<Integer>> response = new Response<>(
                200,
                messageSource.getMessage("response.message.disableUsersSuccess", null, locale),
                "Security-Model",
                null,
                null
        );
        return ResponseEntity.status(response.status()).body(response);
    }

    @PatchMapping("/internal/users/enable")
    public ResponseEntity<Response<List<Integer>>> enableUsers(
        @RequestBody List<Integer> req,
        @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ) {
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("enableUsers");
        loggingService.logInfo("enableUsers API Calling...", logContext);
        authInternalService.enableUsers(req);
        Response<List<Integer>> response = new Response<>(
                200,
                messageSource.getMessage("response.message.enableUsersSuccess", null, locale),
                "Security-Model",
                null,
                null
        );
        return ResponseEntity.status(response.status()).body(response);
    }

    @PostMapping("/internal/ip/track-violation")
    public ResponseEntity<Map<String, String>> trackIpViolation(@RequestBody Map<String, String> request) {
        LogContext logContext = getLogContext("trackIpViolation");
        loggingService.logInfo("trackIpViolation API Calling...", logContext);
        Map<String, String> result = authInternalService.trackIpViolation(request);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    // Update role, permissions, username, password, status - chỉ cho ADMIN
    @PutMapping("/users/{userId}/admin-update")
    @PreAuthorize("hasRole('ADMIN')")
    @Retryable(retryFor = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<Response<UserDto>> adminUpdateUser(
        @PathVariable("userId") Integer userId,
        @Valid @RequestBody AdminUpdateUserDto updateDto,
        @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ) {
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("updateRoleAndPermissions");
        
        loggingService.logInfo("adminUpdateUser API Calling... for userId: " + userId, logContext);
        loggingService.logInfo("adminUpdateUser - updateDto: userId=" + updateDto.getUserId() + 
            ", role=" + updateDto.getRole() + ", username=" + updateDto.getUsername() + 
            ", status=" + updateDto.getStatus() + ", password=" + (updateDto.getPassword() != null ? "***" : "null"), logContext);
        
        // Lấy current user từ SecurityUtils
        UserDto currentUser = securityUtils.getCurrentUserDto();
        // Validate userId trong path phải khớp với userId trong body
        if (!userId.equals(updateDto.getUserId())) {
            throw new ValidationExceptionHandle(
                "User ID in path does not match user ID in body",
                List.of("userId"),
                "Security-Model"
            );
        }
                
        UserDto updatedUser = authService.adminUpdateUser(updateDto, currentUser);
                
        Response<UserDto> response = new Response<>(
            200,
            messageSource.getMessage("response.message.updateSuccess", null, locale),
            "Security-Model",
            null,
            updatedUser
        );
                
        loggingService.logInfo("Successfully updated user by ADMIN for userId: " + userId, logContext);
        return ResponseEntity.status(response.status()).body(response);
    }

    // check account status
    @GetMapping("/check-status")
    public ResponseEntity<Response<Map<String, Object>>> checkAccountStatus(
        @RequestHeader("Authorization") String authHeader,
        @RequestHeader(value = "Accept-Language", defaultValue = "en") String acceptLanguage
    ){
        Locale locale = Locale.forLanguageTag(acceptLanguage);
        LogContext logContext = getLogContext("checkAccountStatus");

        loggingService.logInfo("checkAccountStatus API Calling...", logContext);
        String token = authHeader.substring(7);
        
        String username = authService.getUsernameFromToken(token);
        UserEntity user = userRepo.findByUsername(username).orElseThrow(
                () -> new NotFoundExceptionHandle("", List.of(username), "Security-Model")
        );
        
        Map<String, Object> statusInfo = new HashMap<>();
        statusInfo.put("status", user.getStatus().name());
        statusInfo.put("username", user.getUsername());
        statusInfo.put("userId", user.getUserId());
        
        // Get status message from message source
        String statusMessageKey = switch (user.getStatus()) {
            case PENDING -> "response.status.pending";
            case ENABLED -> "response.status.enabled";
            case FAILED -> "response.status.failed";
            case DISABLED -> "response.status.disabled";
        };
        
        String statusMessage = messageSource.getMessage(statusMessageKey, null, locale);
        statusInfo.put("statusMessage", statusMessage);
        statusInfo.put("canLogin", user.getStatus() == com.model_shared.enums.Status.ENABLED);

        Response<Map<String, Object>> response = new Response<>(
                200,
                messageSource.getMessage("response.message.checkStatusSuccess", null, locale),
                "Security-Model",
                null,
                statusInfo
        );

        loggingService.logInfo("Account status checked for user: " + username, logContext);
        return ResponseEntity.status(response.status()).body(response);
    }
    
    

}
