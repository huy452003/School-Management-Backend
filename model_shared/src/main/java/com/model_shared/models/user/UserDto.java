package com.model_shared.models.user;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;
import java.time.LocalDate;
import com.model_shared.enums.Gender;
import java.util.Map;
import com.model_shared.enums.Type;
import com.model_shared.enums.Role;
import com.model_shared.enums.Permission;
import com.model_shared.enums.Status;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    @NotNull(message = "{validate.user.type.notNull}")
    private Type type;

    @NotNull(message = "{validate.user.userId.notNull}")
    private Integer userId;

    @NotBlank(message = "{validate.user.username.notBlank}")
    @NotNull(message = "{validate.user.username.notNull}")
    private String username;

    @NotBlank(message = "{validate.user.firstName.notBlank}")
    @NotNull(message = "{validate.user.firstName.notNull}")
    private String firstName;

    @NotNull(message = "{validate.user.lastName.notNull}")
    @NotBlank(message = "{validate.user.lastName.notBlank}")
    private String lastName;

    private Integer age;

    @NotNull(message = "{validate.user.gender.notNull}")
    private Gender gender;

    @JsonFormat(pattern = "dd-MM-yyyy")
    @NotNull(message = "{validate.user.birth.notNull}")
    @PastOrPresent(message = "{validate.user.birth.pastOrPresent}")
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

    @NotNull(message = "{validate.user.permissions.notNull}")
    private Set<Permission> permissions;

    @NotNull(message = "{validate.user.status.notNull}")
    private Status status;

    private Map<String, Object> profileData;
}
