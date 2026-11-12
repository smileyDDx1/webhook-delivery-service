package com.debangshu.webhooks.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys look like "whk_" + 43 random characters. The prefix makes a leaked key easy to recognise
 * (secret scanners look for patterns like this).
 */
public final class ApiKeys {

    public static final String PREFIX = "whk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Only this hash is stored. Plain SHA-256 is fine here (it would NOT be fine for passwords): the key is
     * 32 random bytes, so there is nothing to brute force, and a fast hash keeps the check on every request cheap.
     */
    public static String hash(String apiKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    /** The first characters of a key. Safe to show in lists so people can tell their keys apart. */
    public static String displayPrefix(String apiKey) {
        return apiKey.substring(0, Math.min(12, apiKey.length()));
    }
}
