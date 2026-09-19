package com.fdmultimedia.api.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import com.fdmultimedia.api.workers.security.WorkerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfCookieFilter csrfCookieFilter,
            WorkerAuthenticationFilter workerAuthenticationFilter) throws Exception {
        CsrfTokenRequestAttributeHandler csrfTokenRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfTokenRequestHandler)
                        .ignoringRequestMatchers("/api/auth/login", "/api/worker-agent/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Meta's servers must be able to fetch publish media over the public
                        // internet; they cannot present a session cookie or CSRF token. Every
                        // other safety property is enforced by PublicMediaTokenService and
                        // PublicMediaAccessService, not by authentication. GET only.
                        .requestMatchers(HttpMethod.GET, "/api/public-media/**").permitAll()
                        .requestMatchers("/", "/login", "/index.html", "/favicon.ico", "/*.js", "/*.css").permitAll()
                        .requestMatchers("/api/worker-agent/**").hasRole("WORKER")
                        .requestMatchers("/api/assets/**").hasRole("USER")
                        .requestMatchers("/api/jobs/**").hasRole("USER")
                        .requestMatchers("/api/workers/**").hasRole("USER")
                        .requestMatchers("/api/scheduling/**").hasRole("USER")
                        .requestMatchers("/api/social-accounts/**").hasRole("USER")
                        .requestMatchers("/api/publications/**").hasRole("USER")
                        .requestMatchers("/api/content-drafts/**").hasRole("USER")
                        .requestMatchers("/api/publish-schedules/**").hasRole("USER")
                        .requestMatchers("/api/robots/**").hasRole("USER")
                        .requestMatchers("/api/robot-runs/**").hasRole("USER")
                        .requestMatchers("/api/robot-approvals/**").hasRole("USER")
                        .requestMatchers("/api/content-sources/**").hasRole("USER")
                        .requestMatchers("/api/content-suggestions/**").hasRole("USER")
                        .requestMatchers("/api/personas/**").hasRole("USER")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN)))
                .addFilterBefore(workerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfCookieFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
