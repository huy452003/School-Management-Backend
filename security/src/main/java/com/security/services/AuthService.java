package com.security.services;

import com.handle_exceptions.ConflictExceptionHandle;
import com.handle_exceptions.ForbiddenExceptionHandle;
import com.handle_exceptions.NotFoundExceptionHandle;
import com.handle_exceptions.UnauthorizedExceptionHandle;
import com.handle_exceptions.ValidationExceptionHandle;

import com.security.config.JwtConfig;
import com.security.entities.UserEntity;
import com.security.models.Login;
import com.security.models.Register;
import com.security.models.SecurityResponse;
import com.security.models.TokenInfo;
import com.security.repositories.UserRepo;
import com.logging.services.LoggingService;
import com.logging.models.LogContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.kafka_shared.services.KafkaProducerService;
import com.kafka_shared.models.UserEvent;
import com.model_shared.models.user.UserDto;
import com.model_shared.enums.Permission;
import com.model_shared.enums.Role;
import com.model_shared.enums.Status;
import com.model_shared.utils.AgeUtils;
import com.security.utils.UserDtoMapper;
import org.springframework.retry.annotation.Retryable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {
    @Autowired
    UserRepo userRepo;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JwtService jwtService;
    @Autowired
    AuthenticationManager authenticationManager;
    @Autowired
    JwtConfig jwtConfig;
    @Autowired
    LoggingService loggingService;
    @Autowired
    BlacklistService blacklistService;
    @Autowired
    KafkaProducerService kafkaProducerService;
    @Autowired
    UserDtoMapper userDtoMapper;


    private LogContext getLogContext(String methodName) {
        return LogContext.builder()
                .module("security")
                .className(this.getClass().getSimpleName())
                .methodName(methodName)
                .build();
    }

    private String formatExpirationTime(Long expirationMillis) {
        LocalDateTime expirationTime = LocalDateTime.now().plusSeconds(expirationMillis / 1000);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return expirationTime.format(formatter);
    }

    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public SecurityResponse register(Register request){
        LogContext logContext = getLogContext("register");
        logContext.setUserId(request.getUsername());

        loggingService.logInfo("Register attempt for username: " + logContext.getUserId(), logContext);

        // Kiểm tra username đã tồn tại chưa
        UserEntity existingUser = null;
        if(userRepo.existsByUsername(request.getUsername())){
            // Kiểm tra xem user có status FAILED không, nếu có thì có thể cho phép register lại
            existingUser = userRepo.findByUsername(request.getUsername()).orElse(null);
            if (existingUser != null && existingUser.getStatus() == Status.FAILED) {
                loggingService.logInfo("Username exists with FAILED status, allowing re-registration by reusing userId: " 
                    + existingUser.getUserId(), logContext);
            } else {
                loggingService.logDebug("Username already exists with active status", logContext);
                throw new ConflictExceptionHandle("", List.of(request.getUsername()) , "Security-Model");
            }
        }

        // kiểm tra xem role ADMIN có tồn tại trong hệ thống không và chỉ chấp nhận 1 ADMIN
        Role requestedRole = Role.valueOf(request.getRole().name());
        if (requestedRole == Role.ADMIN) {
            validateAdminRoleAssignment(logContext);
        }

        Role userRole = Role.valueOf(request.getRole().name());
        
        // Validate permissions: Các role khác ADMIN phải có permissions
        if (userRole != Role.ADMIN && (request.getPermissions() == null || request.getPermissions().isEmpty())) {
            loggingService.logWarn("Registration attempt without permissions for non-ADMIN role: " + userRole, logContext);
            throw new ValidationExceptionHandle(
                "Permissions are required for non-ADMIN roles",
                List.of("permissions"),
                "Security-Model"
            );
        }
        
        // Convert permissions: ADMIN tự động có tất cả permissions
        Set<Permission> finalPermissions = convertToPermissions(request.getPermissions(), userRole);

        String normalizedPhone = request.getPhoneNumber().trim().toLowerCase();
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // Kiểm tra xem phoneNumber và email đã tồn tại chưa
        if (userRepo.existsByPhoneNumber(normalizedPhone)) {
            throw new ConflictExceptionHandle(
                "Phone number already exists", List.of(normalizedPhone), "Security-Model"
            );
        }
        if (userRepo.existsByEmail(normalizedEmail)) {
            throw new ConflictExceptionHandle(
                "Email already exists", List.of(normalizedEmail), "Security-Model"
            );
        }
        
        UserEntity user;
        if (existingUser != null) {
            // Re-registration: UPDATE user FAILED thay vì tạo mới
            // Giữ nguyên userId để tránh lủng lỗ
            user = existingUser;
            user.setType(request.getType());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setFirstName(request.getFirstName());
            user.setLastName(request.getLastName());
            user.setGender(request.getGender());
            user.setBirth(request.getBirth());
            user.setPhoneNumber(normalizedPhone);
            user.setEmail(normalizedEmail);
            user.setRole(userRole);
            user.setPermissions(finalPermissions); // Sử dụng permissions đã convert
            user.setStatus(Status.PENDING);
            
            loggingService.logInfo("Reusing existing userId: " + user.getUserId() + " for re-registration", logContext);
        } else {
            // New registration: Tạo user mới
            user = UserEntity.builder()
                    .type(request.getType())
                    .username(request.getUsername())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .firstName(request.getFirstName())
                    .lastName(request.getLastName())
                    .gender(request.getGender())
                    .birth(request.getBirth())
                    .phoneNumber(normalizedPhone)
                    .email(normalizedEmail)
                    .role(userRole)
                    .permissions(finalPermissions) // Sử dụng permissions đã convert
                    .status(Status.PENDING)
                    .build();
        }

        if (userRole == Role.ADMIN) {
            user.setStatus(Status.ENABLED);
            userRepo.save(user);
            loggingService.logInfo("ADMIN user registered and enabled immediately (no entity creation needed)", logContext);
        } else {

            userRepo.save(user);
            Map<String, Object> profileData = null;
            if(request.getProfileData() != null && !request.getProfileData().isEmpty()){
                profileData = new HashMap<>();
                profileData.putAll(request.getProfileData());
            }

            UserDto userDto = userDtoMapper.fromEntity(user);
            
            // Set custom field (profileData - không có trong UserEntity)
            userDto.setProfileData(profileData);

            UserEvent userEvent = UserEvent.userRegistered(userDto);
            kafkaProducerService.sendUserEvent(userEvent);
            loggingService.logInfo("User registered event sent to Kafka for " + user.getType() + " user", logContext);
        }

        // Tạo custom claims cho token
        Map<String, Object> claim = new HashMap<>();
        claim.put("role", user.getRole().name());
        claim.put("permissions", user.getPermissions().stream()
                .map(Permission::name)
                .collect(Collectors.toSet()));
        
        String accessToken = jwtService.generateToken(claim, user);
        String refreshToken = jwtService.generateRefreshToken(claim, user);

        return SecurityResponse.builder()
                .status(user.getStatus())
                .userId(user.getUserId())
                .username(user.getUsername())
                .age(AgeUtils.calculateAge(user.getBirth()))
                .gender(user.getGender())
                .birth(user.getBirth())
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail()) 
                .role(user.getRole())
                .permissions(user.getPermissions())
                .accessToken(accessToken)
                .expires(formatExpirationTime(jwtConfig.getExpiration()))
                .refreshToken(refreshToken)
                .refExpires(formatExpirationTime(jwtConfig.getRefreshExpiration()))
                .build();
    }

    public SecurityResponse login(Login request){
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        LogContext logContext = getLogContext("login");
        logContext.setUserId(request.username());

        loggingService.logInfo("Login attempt for username: " + logContext.getUserId(), logContext);
        UserEntity user = userRepo.findByUsername(request.username())
                .orElseThrow(() -> new NotFoundExceptionHandle(
                        "", List.of(request.username()), "Security-Model")
                );

        // Kiểm tra status của user
        if (user.getStatus() == Status.FAILED) {
            loggingService.logWarn("Login attempt for user with FAILED status: " + user.getUsername(), logContext);
            throw new ForbiddenExceptionHandle(
                "Account creation failed",
                "Your account creation process failed. Please contact administrator or register again."
            );
        } else if (user.getStatus() == Status.PENDING) {
            loggingService.logWarn("Login attempt for user with PENDING status: " + user.getUsername(), logContext);
            throw new ForbiddenExceptionHandle(
                "Account pending",
                "Your account is still being created. Please wait a moment and try again."
            );
        } else if (user.getStatus() == Status.DISABLED) {
            loggingService.logWarn("Login attempt for disabled user: " + user.getUsername(), logContext);
            throw new ForbiddenExceptionHandle(
                "Account disabled",
                "Your account has been disabled. Please contact administrator."
            );
        }

        // Tạo custom claims cho token
        Map<String, Object> claim = new HashMap<>();
        claim.put("role", user.getRole().name());
        claim.put("permissions", user.getPermissions().stream()
                .map(Permission::name)
                .collect(Collectors.toSet()));
        
        String accessToken = jwtService.generateToken(claim, user);
        String refreshToken = jwtService.generateRefreshToken(claim, user);

        return SecurityResponse.builder()
                .status(user.getStatus())
                .userId(user.getUserId())
                .username(user.getUsername())
                .age(AgeUtils.calculateAge(user.getBirth()))
                .gender(user.getGender())
                .birth(user.getBirth())
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail()) 
                .role(user.getRole())
                .permissions(user.getPermissions())
                .accessToken(accessToken)
                .expires(formatExpirationTime(jwtConfig.getExpiration()))
                .refreshToken(refreshToken)
                .refExpires(formatExpirationTime(jwtConfig.getRefreshExpiration()))
                .build();
    }

    public Map<String, Object> logout(String token) {
        LogContext logContext = getLogContext("logout");
        
        String username = getUsernameFromToken(token);
            
            // Blacklist tất cả token của user (logout all devices)
            blacklistService.blacklistAllUserTokens(username);
            
            Map<String, Object> logoutResponse = new HashMap<>();
            logoutResponse.put("username", username);
            logoutResponse.put("timestamp",
            java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")));
            
            loggingService.logInfo("User logged out from all devices successfully: " + username
            , logContext);
            return logoutResponse;
    }

    public SecurityResponse refreshToken(String authHeader){
        LogContext logContext = getLogContext("refreshToken");

        String refreshToken = authHeader.substring(7); 
        
        String username = jwtService.extractUsername(refreshToken);

        // Kiểm tra user token có bị blacklist không
        if (blacklistService.isUserTokenBlacklisted(refreshToken, username)) {
            loggingService.logDebug("User token is blacklisted", logContext);
            throw new UnauthorizedExceptionHandle("User token is blacklisted"
            , "User has been logged out from all devices");
        }

        UserEntity user = userRepo.findByUsername(username)
                .orElseThrow(() -> new NotFoundExceptionHandle(
                        "", List.of(username),"Security-Model")
                );
        if (!jwtService.isTokenValid(refreshToken,user)){
            throw new UnauthorizedExceptionHandle("Invalid refresh token"
            , "Refresh token is expired");
        }

        // Tạo custom claims cho new access token
        Map<String, Object> accessClaims = new HashMap<>();
        accessClaims.put("role", user.getRole().name());
        accessClaims.put("permissions", user.getPermissions().stream()
                .map(Permission::name)
                .collect(Collectors.toSet()));
        
        String newAccessToken = jwtService.generateToken(accessClaims, user);

        return SecurityResponse.builder()
                .status(user.getStatus())
                .userId(user.getUserId())
                .username(user.getUsername())
                .age(AgeUtils.calculateAge(user.getBirth()))
                .gender(user.getGender())
                .birth(user.getBirth())
                .role(user.getRole())
                .permissions(user.getPermissions())
                .accessToken(newAccessToken)
                .expires(formatExpirationTime(jwtConfig.getExpiration()))
                .refreshToken(refreshToken)
                .refExpires(formatExpirationTime(jwtService.extractExpiration(refreshToken).getTime()))
                .build();
    }

    public TokenInfo decodeToken(String token){
        LogContext logContext = getLogContext("decodeToken");
        try{
            TokenInfo tokenInfo = TokenInfo.builder()
                .username(jwtService.extractUsername(token))
                .role(jwtService.extractRole(token))
                .permissions(jwtService.extractPermissions(token))
                .expiration(formatExpirationTime(jwtService.extractExpiration(token).getTime()))
                .issuedAt(formatExpirationTime(jwtService.extractIssuedAt(token).getTime()))
                .jti(jwtService.extractJTI(token))
                .isExpired(jwtService.isTokenExpired(token))
                .build();

            loggingService.logInfo("Token decoded successfully", logContext);
            return tokenInfo;
        }catch(Exception e){
            loggingService.logError("Error decoding token", e, logContext);
            return null;
        }
    }

    public String getUsernameFromToken(String token) {
        try {
            return jwtService.extractUsername(token);
        } catch (Exception e) {
            loggingService.logError("Error extracting username from token", e, getLogContext("getUsernameFromToken"));
            throw new UnauthorizedExceptionHandle("Error extracting username from token");
        }
    }

    private void validateAdminRoleAssignment(LogContext logContext) {
        // Kiểm tra xem đã có ADMIN nào trong hệ thống chưa
        long adminCount = userRepo.countByRole(Role.ADMIN);
        
        if (adminCount > 0) {
            loggingService.logWarn("Attempt to create additional ADMIN user - only one ADMIN allowed", logContext);
            throw new ForbiddenExceptionHandle(
                "ADMIN role assignment restricted", 
                "Only one ADMIN user is allowed in the system");
        }
        
        loggingService.logInfo("First ADMIN user being created - this is allowed", logContext);
    }

    private Set<Permission> convertToPermissions(Set<Permission> permissions, Role role) {
        Set<Permission> newPermissions = new HashSet<>();
        
        // ADMIN tự động có tất cả permissions
        if (role == Role.ADMIN) {
            newPermissions.addAll(List.of(Permission.values()));
            loggingService.logDebug("ADMIN role assigned all permissions automatically", getLogContext("convertToPermissions"));
            return newPermissions;
        }
        
        // Các role khác cần permissions cụ thể
        if (permissions != null && !permissions.isEmpty()) {
            newPermissions.addAll(permissions);
        }
        return newPermissions;
    }

    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public UserDto adminUpdateUser(com.model_shared.models.user.AdminUpdateUserDto updateDto, UserDto currentUser) {
        LogContext logContext = getLogContext("adminUpdateUser");
        
        // Kiểm tra chỉ ADMIN mới được update
        if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenExceptionHandle(
                "Only ADMIN can update user information",
                "Insufficient privileges"
            );
        }
        
        UserEntity userEntity = userRepo.findById(updateDto.getUserId())
                .orElseThrow(() -> new NotFoundExceptionHandle(
                    "User not found",
                    List.of(updateDto.getUserId().toString()),
                    "Security-Model"
                ));
        
        Role oldRole = userEntity.getRole();
        
        // Ngăn chặn thay đổi role hoàn toàn
        // Lý do: Mỗi role có dữ liệu riêng (StudentEntity/TeacherEntity), 
        // thay đổi role sẽ gây mất dữ liệu hoặc inconsistency
        // Nếu muốn thay đổi role, nên set status = FAILED và yêu cầu user đăng ký lại
        if (oldRole != updateDto.getRole()) {
            throw new ValidationExceptionHandle(
                "Cannot change user role from " + oldRole + " to " + updateDto.getRole() + 
                ". Each role has specific data (StudentEntity/TeacherEntity). " +
                "To change role, set user status to FAILED and require re-registration with the new role.",
                List.of("role"),
                "Security-Model"
            );
        }
        
        // Convert và update permissions
        Set<Permission> finalPermissions = convertToPermissions(updateDto.getPermissions(), updateDto.getRole());
        userEntity.setPermissions(finalPermissions);
        
        // Update username nếu có
        if (updateDto.getUsername() != null && !updateDto.getUsername().trim().isEmpty()) {
            String newUsername = updateDto.getUsername().trim();
            // Validate username length
            if (newUsername.length() < 3 || newUsername.length() > 50) {
                throw new ValidationExceptionHandle(
                    "Username must be between 3 and 50 characters",
                    List.of("username"),
                    "Security-Model"
                );
            }
            // Kiểm tra username đã tồn tại chưa (trừ chính user này)
            if (userRepo.existsByUsernameAndUserIdNot(newUsername, updateDto.getUserId())) {
                throw new ConflictExceptionHandle(
                    "",
                    List.of(newUsername),
                    "Security-Model"
                );
            }
            userEntity.setUsername(newUsername);
            loggingService.logInfo("Updated username for userId: " + updateDto.getUserId() + " to " + newUsername, logContext);
        }
        
        // Update password nếu có
        if (updateDto.getPassword() != null && !updateDto.getPassword().trim().isEmpty()) {
            String newPassword = updateDto.getPassword().trim();
            // Validate password length
            if (newPassword.length() < 6) {
                throw new ValidationExceptionHandle(
                    "Password must be at least 6 characters",
                    List.of("password"),
                    "Security-Model"
                );
            }
            userEntity.setPassword(passwordEncoder.encode(newPassword));
            loggingService.logInfo("Updated password for userId: " + updateDto.getUserId(), logContext);
        }
        
        // Update status - luôn update (required field)
        if (updateDto.getStatus() != null) {
            Status newStatus = updateDto.getStatus();
            Status oldStatus = userEntity.getStatus();
            userEntity.setStatus(newStatus);
            loggingService.logInfo("Updated status for userId: " + updateDto.getUserId() + " from " + oldStatus + " to " + newStatus, logContext);
        } else {
            loggingService.logWarn("Status is null in updateDto for userId: " + updateDto.getUserId() + ", keeping existing status: " + userEntity.getStatus(), logContext);
        }
        
        UserEntity savedEntity = userRepo.saveAndFlush(userEntity);
        
        loggingService.logInfo(String.format(
            "Admin updated user for userId: %d - Role: %s -> %s, Permissions: %s, Username: %s, Status: %s",
            updateDto.getUserId(),
            oldRole,
            updateDto.getRole(),
            finalPermissions,
            updateDto.getUsername() != null ? "updated" : "unchanged",
            updateDto.getStatus() != null ? updateDto.getStatus() : "unchanged"
        ), logContext);
        
        return userDtoMapper.fromEntity(savedEntity);
    }

}
