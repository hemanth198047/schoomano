package com.schoomano.domain.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UploadRulesTest {

    @Test
    void theTypeComesFromTheBytesNotFromTheSender() {
        assertEquals("application/pdf", UploadRules.typeOf("%PDF-1.7 rest".getBytes(StandardCharsets.US_ASCII)).orElseThrow());
        assertEquals("image/png", UploadRules.typeOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}).orElseThrow());
        assertEquals("image/jpeg", UploadRules.typeOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}).orElseThrow());
        assertTrue(UploadRules.typeOf("<html><script>".getBytes(StandardCharsets.US_ASCII)).isEmpty(), "html is refused");
        assertTrue(UploadRules.typeOf("MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1)).isEmpty(), "a program is refused");
        assertTrue(UploadRules.typeOf(new byte[] {'%', 'P'}).isEmpty());
        assertTrue(UploadRules.typeOf(null).isEmpty());
        assertTrue(UploadRules.typeOf(new byte[] {(byte) 0x89, 'P', 'N', 'G'}).isEmpty(), "a short png header is not enough");
    }

    @Test
    void namesAreCleanedAndGetTheRightExtension() {
        assertEquals("birth.pdf", UploadRules.cleanName("birth.pdf", "application/pdf"));
        assertEquals("passwd.pdf", UploadRules.cleanName("../../etc/passwd", "application/pdf"));
        assertEquals("evil.pdf", UploadRules.cleanName("C:\\Users\\x\\evil.exe", "application/pdf"), "a wrong extension is replaced");
        assertEquals("scan.jpeg", UploadRules.cleanName("scan.JPEG", "image/jpeg"));
        assertEquals("document.png", UploadRules.cleanName("   ", "image/png"));
        assertEquals("hidden.pdf", UploadRules.cleanName(".hidden.pdf", "application/pdf"));
        assertEquals("ab.pdf", UploadRules.cleanName("a\"b<>\r\n.pdf", "application/pdf"));
        assertTrue(UploadRules.cleanName("x".repeat(300) + ".pdf", "application/pdf").length() <= 120);
        assertTrue(UploadRules.cleanName("x".repeat(300) + ".pdf", "application/pdf").endsWith(".pdf"));
    }

    @Test
    void theDownloadHeaderIsAlwaysAnAttachmentAndSafe() {
        String h = UploadRules.contentDisposition("Zoë report.pdf");
        assertTrue(h.startsWith("attachment; "));
        assertTrue(h.contains("filename=\"Zo_ report.pdf\""));
        assertTrue(h.contains("filename*=UTF-8''Zo%C3%AB%20report.pdf"));
        assertFalse(h.contains("\n"));
    }

    @Test
    void theHashIsHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UploadRules.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
