package com.debangshu.webhooks.security;

import com.debangshu.webhooks.application.ApplicationRepository;
import com.debangshu.webhooks.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Two kinds of callers:
 * - the admin key (from the ADMIN_API_KEY env variable) can manage applications and see /actuator
 * - an application key can manage its own endpoints, publish events and read its own deliveries
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ApplicationRepository applications,
                                            AppProperties properties,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        // created here and not as a @Component: Spring Boot registers every Filter bean as a normal
        // servlet filter too, and then it would run twice per request
        var apiKeyFilter = new ApiKeyAuthenticationFilter(applications, properties.security().adminApiKey());

        http
                // no cookies or sessions, every request carries its own key, so CSRF protection isn't needed
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("APP")
                        .anyRequest().denyAll())
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class)
                // 401 and 403 go through GlobalExceptionHandler, so they have the same JSON format as other errors
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));
        return http.build();
    }
}
