package org.kiu.lms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

public final class Money {
    private Money() {
    }

    public static long parseMinor(String text, String currency) {
        int scale = scale(currency);
        try {
            BigDecimal amount = new BigDecimal(text.replace(",", "").trim())
                    .setScale(scale, RoundingMode.HALF_UP);
            return amount.movePointRight(scale).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Enter a valid " + currency + " amount.");
        }
    }

    public static String format(long minor, String currency) {
        int scale = scale(currency);
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(scale);
        format.setMaximumFractionDigits(scale);
        return currency + " " + format.format(BigDecimal.valueOf(minor, scale));
    }

    public static int scale(String currency) {
        return "USD".equals(currency) ? 2 : 0;
    }

    public static BigDecimal toAmount(long minor, String currency) {
        return BigDecimal.valueOf(minor, scale(currency));
    }

    public static long toMinor(BigDecimal amount, String currency) {
        return amount.setScale(scale(currency), RoundingMode.HALF_UP)
                .movePointRight(scale(currency)).longValueExact();
    }
}
