package org.kiu.lms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyTest {
    @Test
    void storesAndFormatsCurrenciesInTheirMinorUnits() {
        assertEquals(123450, Money.parseMinor("1,234.5", "USD"));
        assertEquals(1235, Money.parseMinor("1,234.5", "UGX"));
        assertEquals("USD 1,234.56", Money.format(123456, "USD"));
        assertEquals("UGX 1,235", Money.format(1235, "UGX"));
    }
}
