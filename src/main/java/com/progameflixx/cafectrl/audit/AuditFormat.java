package com.progameflixx.cafectrl.audit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatting for human-readable audit descriptions: "₹1,00,000.00", "Bill #1A2B3C4D", "01 Dec 2026". */
public final class AuditFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private AuditFormat() {}

    /** Integer paise as Indian-grouped rupees. */
    public static String paise(long paise) {
        BigDecimal r = BigDecimal.valueOf(paise).movePointLeft(2);
        return (r.signum() < 0 ? "-₹" : "₹") + group(r.abs());
    }

    /** Rupees (the legacy Double amounts) as Indian-grouped rupees. */
    public static String rupees(Double rupees) {
        if (rupees == null) return "₹0.00";
        BigDecimal r = new BigDecimal(Double.toString(rupees)).setScale(2, java.math.RoundingMode.HALF_UP);
        return (r.signum() < 0 ? "-₹" : "₹") + group(r.abs());
    }

    private static String group(BigDecimal abs) {
        String plain = abs.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String frac = plain.substring(plain.indexOf('.'));
        if (whole.length() <= 3) return whole + frac;
        String last3 = whole.substring(whole.length() - 3);
        String rest = whole.substring(0, whole.length() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) sb.append(',');
            sb.append(rest.charAt(i));
        }
        return sb + "," + last3 + frac;
    }

    /** The short bill number shown on receipts: first 8 characters of the session id, upper case. */
    public static String billNo(String sessionId) {
        if (sessionId == null) return "#?";
        return "#" + sessionId.substring(0, Math.min(8, sessionId.length())).toUpperCase(Locale.ROOT);
    }

    public static String date(LocalDate d) {
        return d == null ? "—" : d.format(DATE);
    }

    public static String dateTime(LocalDateTime d) {
        return d == null ? "—" : d.format(DATE_TIME);
    }
}
