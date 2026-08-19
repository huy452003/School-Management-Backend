package com.security.utils;

import com.model_shared.models.user.UserDto;
import com.model_shared.utils.AgeUtils;
import com.security.entities.UserEntity;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Map {@link UserEntity} → {@link UserDto}: ModelMapper cho các field chung, tuổi lấy từ {@link AgeUtils} (không có trên entity).
 */
@Component
public class UserDtoMapper {

    @Autowired
    private ModelMapper modelMapper;

    public UserDto fromEntity(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        UserDto dto = modelMapper.map(entity, UserDto.class);
        dto.setAge(AgeUtils.calculateAge(entity.getBirth()));
        return dto;
    }
}
