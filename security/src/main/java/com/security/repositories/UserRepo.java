package com.security.repositories;

import com.model_shared.enums.Role;
import com.model_shared.enums.Status;
import com.security.entities.UserEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepo extends JpaRepository<UserEntity, Integer> {
    Optional<UserEntity> findByUsername(String username);
    Optional<UserEntity> findByUserId(Integer userId);
    boolean existsByUsername(String username);
    boolean existsByUsernameAndUserIdNot(String username, Integer userId);
    boolean existsByUserId(Integer userId);
    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByEmail(String email);
    long countByRole(Role role);
    
    // Tìm các user với status cụ thể
    List<UserEntity> findByStatus(Status status);
    
    // Tìm các user PENDING được tạo trước một thời điểm nhất định
    @Query("SELECT u FROM UserEntity u WHERE u.status = :status AND u.userId <= :maxUserId")
    List<UserEntity> findByStatusAndUserIdLessThanEqual(@Param("status") Status status, @Param("maxUserId") Integer maxUserId);

    @Query("SELECT MAX(u.userId) FROM UserEntity u")
    Optional<Integer> findMaxUserId();
}
