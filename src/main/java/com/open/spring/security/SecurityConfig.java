package com.open.spring.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
/*
* To enable HTTP Security in Spring
*/


/*
 * THIS FILE IS IMPORTANT
 * 
 * API Security Configuration
 * 
 * This file configures security for all API endpoints (/api/**) and the JWT authentication endpoint (/authenticate).
 * It uses JWT token-based authentication with stateless sessions.
 * 
 * Key Configuration:
 * - Order(1): This filter chain is processed FIRST before MvcSecurityConfig
 * - Security Matcher: Only handles requests to /api/** and /authenticate
 * - Authentication: Uses JWT tokens via JwtRequestFilter
 * - CSRF: Disabled (standard for stateless JWT APIs)
 * - CORS: Enabled with custom headers for cross-origin requests
 * - Rate Limiting: Applied via RateLimitFilter to prevent abuse
 * 
 * Endpoint Access Levels:
 * - permitAll(): Anyone can access (e.g., /authenticate, /api/person/create)
 * - authenticated(): Requires valid JWT token (e.g., /api/people/**, /api/assets/**)
 * - hasAuthority("ROLE_ADMIN"): Requires admin role (e.g., DELETE /api/person/**)
 * - hasAnyAuthority(...): Requires one of the specified roles (e.g., /api/synergy/**)
 * 
 * IMPORTANT: 
 * - Always set authentication endpoints to permitAll() so users can login without being logged in
 * - Always set account creation endpoints to permitAll() so users can create accounts
 * - For MVC endpoint security (form-based login), see MvcSecurityConfig.java
 * 
 * Filter Chain Order:
 * 1. RateLimitFilter - Rate limiting
 * 2. JwtRequestFilter - JWT token validation
 * 3. Standard Spring Security filters
 */

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtRequestFilter jwtRequestFilter;

    // Inject the RateLimitFilter
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
                          JwtRequestFilter jwtRequestFilter,
                          RateLimitFilter rateLimitFilter) {
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtRequestFilter = jwtRequestFilter;
        this.rateLimitFilter = rateLimitFilter; 
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {

        http
                .securityMatcher("/api/**", "/authenticate")
                // JWT related configuration
                .csrf(csrf -> csrf.disable())
                // .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) OBSOLETE, OVERWRITTEN BY BELOW
                .authorizeHttpRequests(auth -> auth


                        // API ------------------------------------------------------------------------------
                        .requestMatchers(HttpMethod.POST, "/authenticate").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/analytics/**").permitAll()  
                        .requestMatchers(HttpMethod.POST, "/api/person/**").permitAll()          
                        .requestMatchers(HttpMethod.GET,"/api/person/{id}/balance").permitAll() // Allow unauthenticated access to this endpoint
                        .requestMatchers(HttpMethod.GET, "/api/person/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/people/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/assets/upload").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/assets/upload/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/assets/uploads").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/person/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/person/**").hasAuthority("ROLE_ADMIN")
                       
                        .requestMatchers(HttpMethod.GET, "/api/plant/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/plant/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/plant/**").permitAll()
                       
                        .requestMatchers(HttpMethod.POST, "/api/synergy/grades/requests").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/synergy/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                       
                       
                        .requestMatchers(HttpMethod.DELETE, "/api/synergy/saigai/").hasAnyAuthority("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/calendar/add").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/calendar/add_event").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/calendar/edit/{id}").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/calendar/delete/{id}").permitAll()
                   
                        .requestMatchers(HttpMethod.GET,"/api/train/**").authenticated()
                       
                )
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .addHeaderWriter(new StaticHeadersWriter("Access-Control-Allow-Credentials", "true"))
                        .addHeaderWriter(
                                new StaticHeadersWriter("Access-Control-Allow-ExposedHeaders", "*", "Authorization"))
                        .addHeaderWriter(new StaticHeadersWriter("Access-Control-Allow-Headers", "Content-Type",
                                "Authorization", "x-csrf-token"))
                        .addHeaderWriter(new StaticHeadersWriter("Access-Control-Allow-MaxAge", "600"))
                        .addHeaderWriter(new StaticHeadersWriter("Access-Control-Allow-Methods", "POST", "GET",
                                "DELETE", "OPTIONS", "HEAD")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint))


                // Session related configuration
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtRequestFilter.class);

        return http.build();
    }
}