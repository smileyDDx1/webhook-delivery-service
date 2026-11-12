package com.debangshu.webhooks.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatedKeysHaveThePrefixAndAreUnique() {
        String first = ApiKeys.generate();
        String second = ApiKeys.generate();

        assertThat(first).startsWith("whk_").hasSize(4 + 43); // 32 bytes -> 43 base64url characters
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashIsSha256HexAndDeterministic() {
        // sha256("abc") from the FIPS 180-2 test vectors
        assertThat(ApiKeys.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ApiKeys.hash("whk_same")).isEqualTo(ApiKeys.hash("whk_same"));
        assertThat(ApiKeys.hash("whk_a")).isNotEqualTo(ApiKeys.hash("whk_b"));
    }

    @Test
    void displayPrefixShowsOnlyTheStartOfTheKey() {
        assertThat(ApiKeys.displayPrefix("whk_abcdefghijklmnop")).isEqualTo("whk_abcdefgh");
        assertThat(ApiKeys.displayPrefix("short")).isEqualTo("short");
    }
}
