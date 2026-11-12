package com.debangshu.webhooks.security;

/**
 * Who is calling: the application that owns the API key. Controllers get it with @AuthenticationPrincipal.
 */
public record AppPrincipal(long applicationId, String name, int rateLimitPerSecond) {
}
