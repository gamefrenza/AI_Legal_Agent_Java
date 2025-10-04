package com.legalai.agent.config;

import com.legalai.agent.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes
@EnableScheduling
public class SecurityConfig {

    /**
     * Configures HTTP security with role-based access control
     * 
     * - /admin/** endpoints require ADMIN role
     * - /docs/** endpoints require LAWYER or ADMIN role
     * - All other endpoints require authentication
     * - Redis-based session management with maximum 1 concurrent session per user
     * - CSRF disabled for API endpoints
     * - Basic Auth as fallback authentication
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, 
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   SessionRegistry sessionRegistry) throws Exception {
        http
            // Disable CSRF for API endpoints (stateless REST API)
            .csrf(csrf -> csrf.disable())
            
            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // Admin endpoints - ADMIN role only
                .requestMatchers("/admin/**").hasRole("ADMIN")
                
                // Document endpoints - LAWYER or ADMIN roles
                .requestMatchers("/docs/**").hasAnyRole("LAWYER", "ADMIN")
                
                // Public endpoints (if needed, e.g., login, health checks)
                .requestMatchers("/api/auth/login", "/actuator/health").permitAll()
                
                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            
            // Configure Redis-based session management with concurrency control
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1) // Only 1 concurrent session per user
                .maxSessionsPreventsLogin(false) // Expire old session when new login occurs
                .sessionRegistry(sessionRegistry)
                .expiredUrl("/login?expired=true")
            )
            
            // Add JWT authentication filter before UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            
            // Enable HTTP Basic Authentication as fallback
            .httpBasic(basic -> basic.realmName("Legal AI Agent"));

        return http.build();
    }

    /**
     * Password encoder bean using BCrypt
     * BCrypt is a strong hashing function designed for password storage
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * In-memory user details manager with sample users
     * TODO: In production, replace with database-backed UserDetailsService
     * 
     * Sample Users:
     * - lawyer / lawyer123 (ROLE_LAWYER)
     * - admin / admin123 (ROLE_ADMIN)
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        // Create lawyer user with LAWYER role
        UserDetails lawyer = User.builder()
                .username("lawyer")
                .password(passwordEncoder.encode("lawyer123"))
                .roles("LAWYER")
                .build();

        // Create admin user with ADMIN role
        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin123"))
                .roles("ADMIN")
                .build();

        // Create clerk user with CLERK role (bonus user for testing)
        UserDetails clerk = User.builder()
                .username("clerk")
                .password(passwordEncoder.encode("clerk123"))
                .roles("CLERK")
                .build();

        return new InMemoryUserDetailsManager(lawyer, admin, clerk);
    }

    /**
     * JWT Authentication Filter bean
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter();
    }

    /**
     * SessionRegistry bean for tracking active user sessions
     * Used for concurrent session control and session management
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * HttpSessionEventPublisher bean
     * Required for Spring Security to be notified about session lifecycle events
     * This enables proper session tracking in the SessionRegistry
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}

