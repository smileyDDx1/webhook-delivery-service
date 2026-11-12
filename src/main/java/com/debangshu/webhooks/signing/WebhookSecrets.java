package com.debangshu.webhooks.signing;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signing secrets look like "whsec_" + base64 of 24 random bytes, the same format as the
 * Standard Webhooks spec (https://www.standardwebhooks.com), so receivers can use existing libraries.
 */
public final class WebhookSecrets {

    public static final String PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSecrets() {
    }

    public static String generate() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    /** The raw key bytes used for HMAC (the part after "whsec_", base64-decoded). */
    public static byte[] decode(String secret) {
        String encoded = secret.startsWith(PREFIX) ? secret.substring(PREFIX.length()) : secret;
        return Base64.getDecoder().decode(encoded);
    }
}
