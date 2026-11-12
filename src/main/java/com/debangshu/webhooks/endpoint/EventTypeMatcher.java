package com.debangshu.webhooks.endpoint;

import java.util.List;

/**
 * Decides if an endpoint wants an event, based on the endpoint's eventTypes list:
 * - empty list or "*"  : every event
 * - "invoice.paid"     : exactly that type
 * - "invoice.*"        : "invoice.paid", "invoice.payment.failed"... but NOT "invoices.paid"
 *
 * I do the matching in Java instead of SQL because an application only has a handful of endpoints,
 * and the logic is much easier to unit test here.
 */
public final class EventTypeMatcher {

    private EventTypeMatcher() {
    }

    public static boolean matches(List<String> filters, String eventType) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (String filter : filters) {
            if (filter.equals("*") || filter.equals(eventType)) {
                return true;
            }
            if (filter.endsWith(".*")) {
                String prefix = filter.substring(0, filter.length() - 1); // keeps the dot: "invoice."
                if (eventType.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
