package com.debangshu.webhooks.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Small helpers for passing Instants to plain SQL (JdbcClient) and reading them back.
 * The PostgreSQL driver understands OffsetDateTime for TIMESTAMPTZ columns, so I convert to UTC first.
 */
public final class Times {

    private Times() {
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
