package com.schoomano.domain.fees;

import java.util.ArrayList;
import java.util.List;

/** What a well-formed invoice and payment look like, so the API can refuse bad input in plain words. */
public final class InvoiceRules {

    public static final int MAX_LINES = 20;
    /** One crore rupees in paise: far above any school fee, low enough to catch a slipped decimal point. */
    public static final long MAX_TOTAL_PAISE = 1_00_00_000L * 100;

    private InvoiceRules() { }

    public static List<String> problems(List<Long> linePaise) {
        List<String> out = new ArrayList<>();
        if (linePaise.isEmpty()) {
            out.add("Add at least one fee line.");
            return out;
        }
        if (linePaise.size() > MAX_LINES) {
            out.add("An invoice can have at most " + MAX_LINES + " lines.");
        }
        long total = 0;
        for (int i = 0; i < linePaise.size(); i++) {
            long amount = linePaise.get(i);
            if (amount <= 0) {
                out.add("Line " + (i + 1) + " needs an amount above zero.");
            } else if (amount > MAX_TOTAL_PAISE) {
                out.add("Line " + (i + 1) + " is larger than " + Paise.format(MAX_TOTAL_PAISE) + ".");
            } else {
                total += amount;
            }
        }
        if (out.isEmpty() && total > MAX_TOTAL_PAISE) {
            out.add("The invoice total is larger than " + Paise.format(MAX_TOTAL_PAISE) + ".");
        }
        return out;
    }

    /** A payment must be at least one paise and no more than what is still owed. Returns a message, or null if fine. */
    public static String paymentProblem(long totalPaise, long paidPaise, long amountPaise) {
        long due = totalPaise - paidPaise;
        if (due <= 0) {
            return "This invoice is already paid.";
        }
        if (amountPaise <= 0) {
            return "Enter an amount above zero.";
        }
        if (amountPaise > due) {
            return "The amount is more than the " + Paise.format(due) + " still due.";
        }
        return null;
    }
}
