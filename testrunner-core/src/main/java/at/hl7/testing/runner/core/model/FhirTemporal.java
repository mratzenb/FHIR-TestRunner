package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A FHIR {@code date}, {@code dateTime}, {@code instant} or {@code time}, with the precision and the time zone offset
 * it was written with: {@code 2026}, {@code 2026-01}, {@code 2026-01-01}, {@code 2026-01-01T10:00:00.5+01:00},
 * {@code 10:00:00}.
 *
 * <p>{@link #compare} follows the FHIRPath rules for {@code =}, {@code <} and {@code >}: values with an offset are
 * compared as points in time ({@code 10:00+01:00} equals {@code 09:00Z}); seconds and their fraction are one
 * precision ({@code 10:30:31.0} equals {@code 10:30:31}); and a comparison that reaches a precision only one of the
 * two has, cannot be decided ({@code 2026-01} and {@code 2026-01-01}).
 */
public final class FhirTemporal {

    private static final Pattern DATE_TIME = Pattern.compile(
            "(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?:T(\\d{2}):(\\d{2})(?::(\\d{2}(?:\\.\\d+)?))?(Z|[+-]\\d{2}:\\d{2})?)?)?)?");
    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2})(?::(\\d{2}(?:\\.\\d+)?))?");
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final String[] PRECISIONS = {"a year", "a month", "a day", "an hour", "a minute", "seconds"};

    private final String text;
    private final boolean timeOnly;
    /** year, month, day, hour, minute; {@code -1} where absent. A time has no year, month and day. */
    private final int[] parts;
    private final BigDecimal second;
    private final ZoneOffset offset;

    private FhirTemporal(String text, boolean timeOnly, int[] parts, BigDecimal second, ZoneOffset offset) {
        this.text = text;
        this.timeOnly = timeOnly;
        this.parts = parts;
        this.second = second;
        this.offset = offset;
    }

    /** The value of {@code text} if it is a FHIR date, dateTime, instant or time; empty otherwise. */
    @Nonnull
    public static Optional<FhirTemporal> parse(@Nullable String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.trim();
        Matcher date = DATE_TIME.matcher(value);
        if (date.matches()) {
            int[] parts = {number(date.group(1)), number(date.group(2)), number(date.group(3)), number(date.group(4)),
                    number(date.group(5))};
            if (!valid(parts)) {
                return Optional.empty();
            }
            ZoneOffset offset = date.group(7) == null ? null : ZoneOffset.of(date.group(7));
            BigDecimal second = date.group(6) == null ? null : new BigDecimal(date.group(6));
            return second != null && second.compareTo(SIXTY) >= 0 ? Optional.empty()
                    : Optional.of(new FhirTemporal(value, false, parts, second, offset));
        }
        Matcher time = TIME.matcher(value);
        if (time.matches()) {
            int[] parts = {-1, -1, -1, number(time.group(1)), number(time.group(2))};
            BigDecimal second = time.group(3) == null ? null : new BigDecimal(time.group(3));
            return valid(parts) && (second == null || second.compareTo(SIXTY) < 0)
                    ? Optional.of(new FhirTemporal(value, true, parts, second, null)) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The value as written. */
    @Nonnull
    public String text() {
        return text;
    }

    /**
     * Compares by the FHIRPath rules, precision by precision from the year (the hour for a time) down: the first
     * precision whose values differ decides.
     *
     * @return negative, zero or positive as this is before, at the same time as, or after {@code other}
     * @throws IllegalArgumentException if the two cannot be compared: the comparison reaches a precision that only
     *                                  one of them has, only one has a time zone offset, or one is a time and the
     *                                  other a date
     */
    public int compare(@Nonnull FhirTemporal other) {
        if (timeOnly != other.timeOnly) {
            throw new IllegalArgumentException("'" + text + "' and '" + other.text + "' cannot be compared: a time"
                    + " of day and a date");
        }
        FhirTemporal a = this;
        FhirTemporal b = other;
        if (a.hasTime() && b.hasTime() && (a.offset == null) != (b.offset == null)) {
            throw new IllegalArgumentException("'" + text + "' and '" + other.text + "' cannot be compared: only "
                    + "'" + (a.offset != null ? text : other.text) + "' has a time zone offset");
        }
        if (a.offset != null && b.offset != null) {
            a = a.inUtc();
            b = b.inUtc();
        }
        for (int i = timeOnly ? 3 : 0; i < PRECISIONS.length; i++) {
            boolean inA = a.has(i);
            boolean inB = b.has(i);
            if (!inA && !inB) {
                return 0;
            }
            if (inA != inB) {
                throw new IllegalArgumentException("'" + text + "' and '" + other.text + "' cannot be compared: "
                        + "only '" + (inA ? text : other.text) + "' has " + PRECISIONS[i]);
            }
            int order = i < 5 ? Integer.compare(a.parts[i], b.parts[i]) : a.second.compareTo(b.second);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private boolean hasTime() {
        return parts[3] >= 0;
    }

    private boolean has(int precision) {
        return precision < 5 ? parts[precision] >= 0 : second != null;
    }

    /** The same point in time with offset {@code Z}; precision and seconds stay (offsets are whole minutes). */
    private FhirTemporal inUtc() {
        if (!hasTime() || ZoneOffset.UTC.equals(offset)) {
            return this;
        }
        OffsetDateTime utc = OffsetDateTime.of(LocalDateTime.of(parts[0], parts[1], parts[2], parts[3], parts[4]),
                offset).withOffsetSameInstant(ZoneOffset.UTC);
        int[] moved = {utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(), utc.getHour(), utc.getMinute()};
        return new FhirTemporal(text, false, moved, second, ZoneOffset.UTC);
    }

    /** Month 1–12, a day that exists in it, hour 0–23, minute 0–59 – where present. */
    private static boolean valid(int[] parts) {
        if (parts[1] > 12 || parts[1] == 0 || parts[3] > 23 || parts[4] > 59) {
            return false;
        }
        try {
            if (parts[2] >= 0) {
                LocalDate.of(parts[0], parts[1], parts[2]);
            }
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static int number(String digits) {
        return digits == null ? -1 : Integer.parseInt(digits);
    }

    /** Equal if written the same; {@link #compare} decides whether two values are the same point in time. */
    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof FhirTemporal other && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    /** The value as written, so that messages show what the TestScript or the server wrote. */
    @Override
    @Nonnull
    public String toString() {
        return text;
    }
}
