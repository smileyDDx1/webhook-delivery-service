package com.debangshu.webhooks.security;

import com.debangshu.webhooks.application.ApplicationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Reads "Authorization: Bearer <key>" and logs the caller in as either the admin or an application.
 *
 * If the key is missing or wrong the request just continues without a user, and Spring Security's
 * authorization rules (in SecurityConfig) answer with 401. That way this filter doesn't need its own
 * error handling.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApplicationRepository applications;
    private final byte[] adminKey;

    public ApiKeyAuthenticationFilter(ApplicationRepository applications, String adminKey) {
        this.applications = applications;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = readBearerToken(request);
        if (key != null) {
            Authentication authentication = authenticate(key);
            if (authentication != null) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }

    private static String readBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private Authentication authenticate(String key) {
        // MessageDigest.isEqual compares in constant time, so the response time doesn't
        // tell an attacker how many characters of their guess were right
        if (MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), adminKey)) {
            return UsernamePasswordAuthenticationToken.authenticated("admin", null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        }
        if (!key.startsWith(ApiKeys.PREFIX)) {
            return null;
        }
        // looking the key up by its hash: the database never sees the real key
        return applications.findByApiKeyHash(ApiKeys.hash(key))
                .map(app -> UsernamePasswordAuthenticationToken.authenticated(
                        new AppPrincipal(app.getId(), app.getName(), app.getRateLimitPerSecond()), null,
                        List.of(new SimpleGrantedAuthority("ROLE_APP"))))
                .orElse(null);
    }
}
