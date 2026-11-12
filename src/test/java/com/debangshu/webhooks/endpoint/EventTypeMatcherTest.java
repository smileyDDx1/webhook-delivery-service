package com.debangshu.webhooks.endpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventTypeMatcherTest {

    @Test
    void emptyListMatchesEverything() {
        assertThat(EventTypeMatcher.matches(List.of(), "invoice.paid")).isTrue();
        assertThat(EventTypeMatcher.matches(null, "invoice.paid")).isTrue();
    }

    @ParameterizedTest(name = "filters [{0}] and event {1} -> {2}")
    @CsvSource({
            "'invoice.paid',                 invoice.paid,           true",
            "'invoice.paid',                 invoice.created,        false",
            "'*',                            customer.deleted,       true",
            "'invoice.*',                    invoice.paid,           true",
            "'invoice.*',                    invoice.payment.failed, true",
            "'invoice.*',                    invoices.paid,          false",
            "'invoice.*',                    invoice,                false",
            "'invoice.paid;customer.*',      customer.created,       true",
            "'invoice.paid;customer.*',      order.shipped,          false",
            "'Invoice.Paid',                 invoice.paid,           false"
    })
    void matching(String filters, String eventType, boolean expected) {
        List<String> filterList = Arrays.asList(filters.split(";"));

        assertThat(EventTypeMatcher.matches(filterList, eventType)).isEqualTo(expected);
    }
}
