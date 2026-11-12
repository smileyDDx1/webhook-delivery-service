package com.debangshu.webhooks.sending;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterParserTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void secondsFormat() {
        assertThat(RetryAfterParser.parse("120", NOW)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryAfterParser.parse(" 0 ", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void httpDateFormat() {
        assertThat(RetryAfterParser.parse("Fri, 13 Nov 2026 10:05:00 GMT", NOW)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void dateInThePastMeansRetryNow() {
        assertThat(RetryAfterParser.parse("Thu, 12 Nov 2026 10:00:00 GMT", NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void missingOrInvalidValuesAreIgnored() {
        assertThat(RetryAfterParser.parse(null, NOW)).isNull();
        assertThat(RetryAfterParser.parse("", NOW)).isNull();
        assertThat(RetryAfterParser.parse("-5", NOW)).isNull();
        assertThat(RetryAfterParser.parse("soon", NOW)).isNull();
    }
}
