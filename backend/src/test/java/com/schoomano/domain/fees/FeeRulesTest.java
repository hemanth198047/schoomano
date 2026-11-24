package com.schoomano.domain.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FeeRulesTest {

    @Test
    void rupeesAreGroupedTheIndianWay() {
        assertEquals("₹0.00", Paise.format(0));
        assertEquals("₹0.05", Paise.format(5));
        assertEquals("₹999.99", Paise.format(99999));
        assertEquals("₹1,000.00", Paise.format(100000));
        assertEquals("₹12,345.60", Paise.format(1234560));
        assertEquals("₹1,23,456.78", Paise.format(12345678));
        assertEquals("₹12,34,567.89", Paise.format(123456789));
        assertEquals("₹1,00,00,000.00", Paise.format(1_00_00_000L * 100));
        assertEquals("-₹250.00", Paise.format(-25000));
    }

    @Test
    void invoicesNeedPositiveLinesWithinLimits() {
        assertTrue(InvoiceRules.problems(List.of(500000L, 120000L)).isEmpty());
        assertEquals(List.of("Add at least one fee line."), InvoiceRules.problems(List.of()));
        assertEquals(List.of("Line 2 needs an amount above zero."), InvoiceRules.problems(List.of(100L, 0L)));
        assertEquals(1, InvoiceRules.problems(List.of(-5L)).size());
        assertEquals(1, InvoiceRules.problems(java.util.Collections.nCopies(21, 100L)).size());
        assertTrue(InvoiceRules.problems(List.of(InvoiceRules.MAX_TOTAL_PAISE, 1L)).get(0).startsWith("The invoice total"));
    }

    @Test
    void paymentsMayNotExceedWhatIsDue() {
        assertNull(InvoiceRules.paymentProblem(500000, 200000, 300000));
        assertNull(InvoiceRules.paymentProblem(500000, 200000, 1));
        assertEquals("The amount is more than the ₹3,000.00 still due.", InvoiceRules.paymentProblem(500000, 200000, 300001));
        assertEquals("Enter an amount above zero.", InvoiceRules.paymentProblem(500000, 0, 0));
        assertEquals("This invoice is already paid.", InvoiceRules.paymentProblem(500000, 500000, 100));
    }
}
