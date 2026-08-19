package com.security.entities;

import jakarta.persistence.*;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.model_shared.enums.Gender;
import com.model_shared.enums.Permission;
import com.model_shared.enums.Role;
import com.model_shared.enums.Status;
import com.model_shared.enums.Type;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder    
@Entity
@Table(name = "users")
public class UserEntity implements UserDetails {
    @Id
    @Column(name = "user_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private Type type;

    @Column(name = "username", unique = true)
    private String username;

    @Column(name = "password")
    private String password;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Column(name ="birth")
    private LocalDate birth;

    @Column(name = "phone_number", unique = true)
    private String phoneNumber;

    @Column(name = "email", unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;

    @ElementCollection(targetClass = Permission.class, fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<Permission> permissions = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private Status status = Status.PENDING;

    // private boolean enabled = true;
    // private boolean accountNonExpired = true;
    // private boolean credentialsNonExpired = true;
    // private boolean accountNonLocked = true;

    @Version
    @Column(name = "version")
    private Long version;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Combine role and permissions into authorities
        List<GrantedAuthority> authorities = permissions.stream()
                .map(permission -> new SimpleGrantedAuthority(permission.name()))
                .collect(Collectors.toList());
        
        // Add role as authority với prefix "ROLE_" để Spring Security @PreAuthorize("hasRole('ADMIN')") hoạt động đúng
        // Spring Security's hasRole() tự động thêm prefix "ROLE_" khi check, nên cần set authority là "ROLE_ADMIN"
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        
        return authorities;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    // @Override
    // public boolean isAccountNonExpired() {
    //     return true;
    // }

    // @Override
    // public boolean isAccountNonLocked() {
    //     return true;
    // }

    // @Override
    // public boolean isCredentialsNonExpired() {
    //     return true;
    // }

    @Override
    public boolean isEnabled() {
        return status.equals(Status.ENABLED);
    }
}
