package com.quantplatform.auth.service;

import com.quantplatform.auth.security.JwtService;
import com.quantplatform.auth.user.User;
import com.quantplatform.auth.user.UserRepository;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$3O1D6f5RNsTUKMQM9KuB7eQjN8bD7zj1y0Y8IGzqXvV6h1oj6K8oK";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public User register(String username, String password) {
        String normalizedUsername = normalize(username);
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new UsernameAlreadyExistsException();
        }

        User user = new User(normalizedUsername, passwordEncoder.encode(password));
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new UsernameAlreadyExistsException();
        }
    }

    @Transactional(readOnly = true)
    public JwtService.IssuedToken login(String username, String password) {
        User user = userRepository.findByUsername(normalize(username)).orElse(null);
        String hash = user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
        if (!passwordEncoder.matches(password, hash) || user == null) {
            throw new InvalidCredentialsException();
        }
        return jwtService.issue(user);
    }

    private String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
