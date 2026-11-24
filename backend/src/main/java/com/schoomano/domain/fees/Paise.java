package com.schoomano.domain.fees;

/** Money is whole paise (a long). Formatting follows Indian digit grouping: ₹12,34,567.89. */
public final class Paise {

    private Paise() { }

    public static String format(long paise) {
        boolean negative = paise < 0;
        long abs = Math.abs(paise);
        String rupees = Long.toString(abs / 100);
        StringBuilder grouped = new StringBuilder();
        int n = rupees.length();
        for (int i = 0; i < n; i++) {
            int fromEnd = n - i;
            grouped.append(rupees.charAt(i));
            boolean lastThree = fromEnd == 4;
            boolean lakhsGroup = fromEnd > 4 && (fromEnd - 3) % 2 == 1;
            if (fromEnd > 3 && (lastThree || lakhsGroup)) {
                grouped.append(',');
            }
        }
        return (negative ? "-" : "") + "₹" + grouped + String.format(".%02d", abs % 100);
    }
}
