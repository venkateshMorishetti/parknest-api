package com.parknest.api.common;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/** Times go out as ISO 8601 in India time ("2026-11-13T10:30:00+05:30"), like the mock data. */
public final class Times {

    private Times() {}

    public static String ist(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(Responses.IST).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
