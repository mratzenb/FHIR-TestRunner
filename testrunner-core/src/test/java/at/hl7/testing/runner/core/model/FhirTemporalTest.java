package at.hl7.testing.runner.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirTemporalTest {

    static FhirTemporal of(String text) {
        return FhirTemporal.parse(text).orElseThrow();
    }

    @Test
    void readsEveryPrecisionOfDateDateTimeInstantAndTime() {
        assertThat(FhirTemporal.parse("2026")).isPresent();
        assertThat(FhirTemporal.parse("2026-02")).isPresent();
        assertThat(FhirTemporal.parse("2026-02-28")).isPresent();
        assertThat(FhirTemporal.parse("2026-02-28T10:00")).isPresent();
        assertThat(FhirTemporal.parse("2026-02-28T10:00:00.123+01:00")).isPresent();
        assertThat(FhirTemporal.parse(" 10:15:30 ")).map(FhirTemporal::text).contains("10:15:30");
        assertThat(FhirTemporal.parse("2026-02-30")).as("no such day").isEmpty();
        assertThat(FhirTemporal.parse("2026-13")).isEmpty();
        assertThat(FhirTemporal.parse("2026-01-01T24:00:00Z")).isEmpty();
        assertThat(FhirTemporal.parse("10:15:60")).isEmpty();
        assertThat(FhirTemporal.parse("200")).as("a number, not a year").isEmpty();
        assertThat(FhirTemporal.parse("Patient/2026")).isEmpty();
        assertThat(FhirTemporal.parse(null)).isEmpty();
    }

    @Test
    void comparesPointsInTimeAcrossOffsets() {
        assertThat(of("2026-01-01T10:00:00+01:00").compare(of("2026-01-01T09:00:00Z"))).isZero();
        assertThat(of("2026-01-01T00:30:00+01:00").compare(of("2025-12-31T23:30:00Z"))).as("across a year").isZero();
        assertThat(of("2026-01-01T10:00:00+01:00").compare(of("2026-01-01T09:30:00Z"))).isNegative();
        assertThat(of("2026-01-01T10:00:00-00:00").compare(of("2026-01-01T10:00:00Z"))).isZero();
    }

    @Test
    void secondsAndTheirFractionAreOnePrecision() {
        assertThat(of("2026-01-01T10:30:31.0Z").compare(of("2026-01-01T10:30:31Z"))).isZero();
        assertThat(of("2026-01-01T10:30:31.5Z").compare(of("2026-01-01T10:30:31Z"))).isPositive();
        assertThat(of("10:00:00.000").compare(of("10:00:00"))).isZero();
    }

    @Test
    void aDifferenceBeforeTheMissingPrecisionDecides() {
        assertThat(of("2026").compare(of("2025-12-31"))).isPositive();
        assertThat(of("2026-03").compare(of("2026-02-28"))).isPositive();
        assertThat(of("2026-01-01").compare(of("2026-01-01"))).isZero();
    }

    @Test
    void valuesThatCannotBeComparedSayWhy() {
        assertThatThrownBy(() -> of("2026-01").compare(of("2026-01-15")))
                .hasMessage("'2026-01' and '2026-01-15' cannot be compared: only '2026-01-15' has a day");
        assertThatThrownBy(() -> of("2026-01-01T10:00:00").compare(of("2026-01-01T10:00:00Z")))
                .hasMessageContaining("only '2026-01-01T10:00:00Z' has a time zone offset");
        assertThatThrownBy(() -> of("10:00").compare(of("2026-01-01")))
                .hasMessageContaining("a time of day and a date");
        assertThatThrownBy(() -> of("2026-01-01T10:00Z").compare(of("2026-01-01T10:00:00Z")))
                .hasMessageContaining("only '2026-01-01T10:00:00Z' has seconds");
    }
}
