package com.chala.posapp.service;

import com.chala.posapp.entity.User;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.UserRepository;
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
        return new org.springframework.security.core.userdetails.User(
                u.getUsername(),
                u.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))
        );
    }
}

