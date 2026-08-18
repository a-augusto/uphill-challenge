package com.uphill.appointments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Two things this API restricts, both HTTP Basic, same {@code admin}/
 * {@code admin} credentials (see DECISIONS.md #010/#035/#036):
 * {@code GET /api/appointments} (the JSON "admin" listing endpoint) and
 * everything under {@code /admin/appointments} (the server-rendered admin
 * UI). Everything else - booking, cancellation, room availability,
 * Swagger, actuator - stays open, exactly as before.
 *
 * <p>CSRF is exempted only for {@code /api/**}: that's a stateless JSON
 * API, no browser session ever drives it, so a CSRF token would be dead
 * weight. The {@code /admin/**} pages are real browser-rendered forms
 * though, and Basic auth is *more* exposed to CSRF than cookie sessions -
 * browsers auto-attach cached Basic credentials to same-origin requests
 * with zero interaction - so those forms get real CSRF protection.
 * {@link CookieCsrfTokenRepository} (double-submit cookie), not the
 * session-backed default, so this still needs no HTTP session anywhere -
 * {@link SessionCreationPolicy#STATELESS} stays true for the whole app.
 *
 * <p>{@code csrf(...).sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())}
 * replaces the strategy {@link org.springframework.security.config.annotation.web.configurers.CsrfConfigurer}
 * uses internally to rotate the CSRF token on every successful
 * authentication (a real fixation defense, but one that assumes
 * session-based login) - note this is a *separate* setter from
 * {@code sessionManagement().sessionAuthenticationStrategy(...)}, which
 * does not affect CSRF at all despite the identical method name. HTTP
 * Basic re-authenticates on *every request*, so left at the default, the
 * CSRF cookie was being deleted and reissued on every single request
 * (confirmed via raw {@code Set-Cookie} headers) - a page rendered a
 * moment earlier (e.g. the booking form) would carry an
 * already-invalidated token by the time it was submitted, failing with
 * 403 on the second and every subsequent request. There is no session
 * here to fixate - the no-op strategy removes exactly that one side
 * effect, nothing else.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .ignoringRequestMatchers("/api/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/appointments").authenticated()
                        .requestMatchers("/admin/appointments/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
