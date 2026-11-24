package com.schoomano.domain.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmsTextTest {

    @Test
    void shortTitlesAreKeptAndPointToTheApp() {
        assertEquals("Schoomano: Sports day moved to Friday. Open Schoomano for details.",
            SmsText.forAnnouncement("  Sports   day moved to Friday "));
    }

    @Test
    void longTitlesAreCutToFitOneSms() {
        String text = SmsText.forAnnouncement("A".repeat(300));
        assertTrue(text.length() <= SmsText.MAX, "length " + text.length());
        assertTrue(text.contains("...."), text);
        assertTrue(text.endsWith("Open Schoomano for details."));
    }

    @Test
    void aTitleIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> SmsText.forAnnouncement("   "));
    }
}
