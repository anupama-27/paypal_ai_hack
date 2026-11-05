package com.cashflowai.time;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Business "today". In the demo it can be moved forward so follow-ups and
 * broken promises can be shown without waiting real days.
 */
@Component
public class DemoClock {

    private volatile int offsetDays;

    public LocalDate today() {
        return LocalDate.now().plusDays(offsetDays);
    }

    public Instant now() {
        return Instant.now().plus(offsetDays, ChronoUnit.DAYS);
    }

    public int offsetDays() {
        return offsetDays;
    }

    public void advance(int days) {
        offsetDays += days;
    }

    public void reset() {
        offsetDays = 0;
    }
}
