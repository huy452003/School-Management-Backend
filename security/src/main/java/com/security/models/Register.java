package com.security.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Email;
import java.time.LocalDate;
import java.util.Set;
import java.util.Map;

import com.model_shared.enums.Gender;
import com.model_shared.enums.Permission;
import com.model_shared.enums.Role;
import com.model_shared.enums.Type;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Register {

    @NotNull(message = "{validate.user.type.notNull}")
    private Type type;

    @NotBlank(message = "{validate.user.username.notBlank}")
    @NotNull(message = "{validate.user.username.notNull}")
    @Size(min = 3, max = 50, message = "{validate.user.username.size}")
    private String username;
    
    @NotBlank(message = "{validate.user.password.notBlank}")
    @NotNull(message = "{validate.user.password.notNull}")
    @Size(min = 6, message = "{validate.user.password.size}")
    private String password;
    
    @NotBlank(message = "{validate.user.firstName.notBlank}")
    @NotNull(message = "{validate.user.firstName.notNull}")
    @Size(max = 50, message = "{validate.user.firstName.size}")
    private String firstName;
    
    @NotBlank(message = "{validate.user.lastName.notBlank}")
    @NotNull(message = "{validate.user.lastName.notNull}")
    @Size(max = 50, message = "{validate.user.lastName.size}")
    private String lastName;

    @NotNull(message = "{validate.user.gender.notNull}")
    private Gender gender;

    @JsonFormat(pattern = "dd-MM-yyyy")
    @PastOrPresent(message = "{validate.user.birth.pastOrPresent}")
    @NotNull(message = "{validate.user.birth.notNull}")
    private LocalDate birth;

    @NotBlank(message = "{validate.user.phoneNumber.notBlank}")
    @NotNull(message = "{validate.user.phoneNumber.notNull}")
    @Size(max = 11, min = 10, message = "{validate.user.phoneNumber.size}")
    private String phoneNumber;

    @NotBlank(message = "{validate.user.email.notBlank}")
    @NotNull(message = "{validate.user.email.notNull}")
    @Email(message = "{validate.user.email.type}")
    private String email;
    
    @NotNull(message = "{validate.user.role.notNull}")
    private Role role;
    
    private Set<Permission> permissions;
    
    private Map<String, Object> profileData;
}
