package com.chala.posapp.repository;

import com.chala.posapp.entity.User;
import com.chala.posapp.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    List<User> findAllByBranchId(Long branchId);
    long countByRoleAndEnabledTrue(Role role);

    @Query(value = "SELECT * FROM users WHERE role = 'ADMIN' ORDER BY id ASC LIMIT 1", nativeQuery = true)
    Optional<User> findFirstAdminNative();

    @Query("""
        SELECT u
        FROM User u
        WHERE u.enabled = true
          AND u.role IN :roles
          AND (:branchId IS NULL OR :branchId = 0 OR u.branchId = :branchId OR u.role = com.chala.posapp.entity.Role.ADMIN)
        ORDER BY u.username ASC
    """)
    List<User> findSalesFilterUsers(@Param("roles") List<Role> roles, @Param("branchId") Long branchId);
}

