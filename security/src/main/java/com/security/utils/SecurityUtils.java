package com.security.utils;

import com.model_shared.models.user.UserDto;
import com.security.entities.UserEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Utility class để lấy thông tin user từ SecurityContextHolder
 * Sử dụng trong module security để tránh duplicate validation (không cần gọi API)
 */
@Component
public class SecurityUtils {
    
    @Autowired
    private UserDtoMapper userDtoMapper;
    
    // Lấy UserEntity từ SecurityContextHolder
    public UserEntity getCurrentUserEntity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        
        // Principal là UserEntity (được set trong JwtAuthFilter)
        if (authentication.getPrincipal() instanceof UserEntity) {
            return (UserEntity) authentication.getPrincipal();
        }
        
        return null;
    }
    
    // Lấy UserDto từ SecurityContextHolder (convert từ UserEntity)
    public UserDto getCurrentUserDto() {
        UserEntity userEntity = getCurrentUserEntity();
        if (userEntity == null) {
            return null;
        }
        return userDtoMapper.fromEntity(userEntity);
    }
    
    // Kiểm tra user hiện tại có role ADMIN không
    public boolean isAdmin() {
        UserEntity userEntity = getCurrentUserEntity();
        if (userEntity == null) {
            return false;
        }
        return userEntity.getRole() != null && 
               userEntity.getRole().name().equals("ADMIN");
    }
}

