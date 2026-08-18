package com.uphill.appointments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The only endpoint this API restricts is {@code GET /api/appointments} -
 * the "admin" listing endpoint (see DECISIONS.md #010) - everything else
 * (booking, cancellation, room availability, Swagger, actuator) stays open,
 * exactly as it was before this class existed. Credentials come from
 * {@code spring.security.user.name}/{@code .password} in
 * {@code application.properties}; Boot auto-creates the in-memory user from
 * those, no {@code UserDetailsService} bean needed here.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/appointments").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
