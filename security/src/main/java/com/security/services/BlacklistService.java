package com.security.services;

import com.logging.models.LogContext;
import com.logging.services.LoggingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Service
public class BlacklistService {
    
    @Autowired
    @Qualifier("customStringRedisTemplate")
    private RedisTemplate<String, String> stringRedisTemplate;
    
    @Autowired
    private JwtService jwtService;
    
    @Autowired
    private LoggingService loggingService;
    
    private static final String BLACKLIST_PREFIX = "blacklist:";
    
    private LogContext getLogContext(String methodName) {
        return LogContext.builder()
                .module("security")
                .className(this.getClass().getSimpleName())
                .methodName(methodName)
                .build();
    }
    

    public void blacklistAllUserTokens(String username) {
        try {
            // Lưu timestamp logout để so sánh với token issued time
            long logoutTime = System.currentTimeMillis();
            stringRedisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + "user:" + username,
                String.valueOf(logoutTime),
                Duration.ofDays(7) // TTL dài hơn để đảm bảo
            );
            
            String readableTime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            loggingService.logInfo("All tokens blacklisted for user: " + username + 
                " at: " + readableTime, getLogContext("blacklistAllUserTokens"));
        } catch (Exception e) {
            loggingService.logError("Error blacklisting all user tokens: " + e.getMessage(), e, 
                getLogContext("blacklistAllUserTokens"));
            throw new RuntimeException("Failed to blacklist all user tokens", e);
        }
    }
    
    public boolean isUserTokenBlacklisted(String token, String username) {
        try {
            // Kiểm tra user có bị blacklist không
            String userBlacklistKey = BLACKLIST_PREFIX + "user:" + username;
            String logoutTimeStr = stringRedisTemplate.opsForValue().get(userBlacklistKey);
            
            if (logoutTimeStr != null) {
                long logoutTime = Long.parseLong(logoutTimeStr);
                Date tokenIssuedAt = jwtService.extractIssuedAt(token);
                
                // Nếu token được phát hành trước thời điểm logout toàn bộ, nó bị blacklist
                return tokenIssuedAt != null && tokenIssuedAt.getTime() < logoutTime;
            }
            
            return false;
        } catch (Exception e) {
            loggingService.logError("Error checking user token blacklist: " + e.getMessage(), e, 
                getLogContext("isUserTokenBlacklisted"));
            // Nếu có lỗi khi kiểm tra, coi như token không bị blacklist để tránh block user
            return false;
        }
    }
}