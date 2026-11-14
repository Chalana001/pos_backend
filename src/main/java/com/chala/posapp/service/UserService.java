package com.chala.posapp.service;

import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.rbac.TenantRbacJdbcService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final TenantRbacJdbcService tenantRbacJdbcService;

    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = getByUsername(username);

        if (!u.isEnabled()) throw new UsernameNotFoundException("User disabled");

        return toUserDetails(u);
    }

    /**
     * JwtAuthFilter needs the entity (to read {@code tokenValidFrom}) <em>and</em> the
     * UserDetails. Exposing the mapping lets it do both from a single row read instead of
     * loading the same user twice on every authenticated request.
     */
    public UserDetails toUserDetails(User u) {
        String legacyRole = u.getRole() == null ? Role.CASHIER.name() : u.getRole().name();
        if (Role.SUPER_ADMIN.name().equals(legacyRole)) {
            return new org.springframework.security.core.userdetails.User(
                    u.getUsername(), u.getPasswordHash(),
                    List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        }
        try {
            String fallbackRole = legacyRole;
            return tenantRbacJdbcService.withTenantJdbc(jdbc -> {
                Long roleId = jdbc.query("SELECT role_id FROM users WHERE id = ?", rs ->
                        rs.next() ? (Long) rs.getObject(1) : null, u.getId());
                String effectiveRole = null;
                if (roleId != null) {
                    String compatibility = jdbc.query("SELECT legacy_compatibility FROM roles WHERE id = ? AND is_active = TRUE",
                            rs -> rs.next() ? rs.getString(1) : null, roleId);
                    effectiveRole = compatibility == null || compatibility.isBlank()
                            || "NONE".equalsIgnoreCase(compatibility) ? null : compatibility;
                }
                return new org.springframework.security.core.userdetails.User(
                        u.getUsername(), u.getPasswordHash(),
                        effectiveRole == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + effectiveRole)));
            });
        } catch (RuntimeException ignored) {
            // Legacy pos_db and unresolved RBAC tenants retain their verified enum behavior.
            return new org.springframework.security.core.userdetails.User(
                    u.getUsername(), u.getPasswordHash(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + legacyRole)));
        }
    }
}

