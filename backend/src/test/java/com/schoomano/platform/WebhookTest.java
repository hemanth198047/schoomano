package com.schoomano.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** B34: the signature, the address rules and one real post to a local receiver. */
class WebhookTest {

    @Test
    void duringRotationBothSecretsSignAndTheNewOneComesFirst() {
        String both = WebhookSigner.header(List.of("whsec_new", "whsec_test"), 1700000000L, "{\"a\":1}");
        assertEquals(WebhookSigner.header("whsec_new", 1700000000L, "{\"a\":1}") + ",v1=38877139021993b830af32feea6e18a8da83eb2f6e49ee50bd9e4cf4ca4d3789", both);
    }

    @Test
    void secretsAreSealedWithAFreshNonceAndOnlyOpenWithTheirKey() {
        String k1 = java.util.Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        String k2 = java.util.Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        SecretBox box = new SecretBox(k1);
        String a = box.seal("whsec_abc");
        assertTrue(a.startsWith("v2:"));
        assertFalse(a.contains("whsec_abc"));
        assertFalse(a.equals(box.seal("whsec_abc")), "a new nonce every time");
        assertEquals("whsec_abc", box.open(a));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new SecretBox(k2).open(a));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new SecretBox("").open(a));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new SecretBox("c2hvcnQ="));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new SecretBox("", true));
        assertTrue(new SecretBox(k1, true).encrypting());
        SecretBox plain = new SecretBox("");
        assertEquals("whsec_abc", plain.seal("whsec_abc"));
        assertEquals("whsec_abc", box.open("whsec_abc"), "a value stored before encryption was turned on still reads");
    }

    @Test
    void theKeyCanBeChangedWhileTheOldOneIsStillListed() {
        java.util.function.Function<String, String> k = t -> java.util.Base64.getEncoder().encodeToString(t.getBytes(StandardCharsets.UTF_8));
        String oldKey = k.apply("0123456789abcdef0123456789abcdef");
        String newKey = k.apply("fedcba9876543210fedcba9876543210");
        SecretBox before = new SecretBox(oldKey);
        String sealed = before.seal("whsec_abc");
        SecretBox during = new SecretBox(newKey, oldKey, false);
        assertEquals("whsec_abc", during.open(sealed), "the old key still reads");
        assertTrue(during.needsRekey(sealed));
        assertTrue(during.needsRekey("whsec_plain"), "a value stored in the clear is sealed too");
        String moved = during.seal(during.open(sealed));
        assertFalse(during.needsRekey(moved));
        assertEquals("whsec_abc", during.open(moved));
        // After the old key is removed, only what was moved still reads.
        SecretBox after = new SecretBox(newKey);
        assertEquals("whsec_abc", after.open(moved));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> after.open(sealed));
        // Values written in the first format (no key label) are found by trying each key.
        String first = "v1:" + sealed.substring(sealed.indexOf(':', 3) + 1);
        assertEquals("whsec_abc", during.open(first));
        assertTrue(during.needsRekey(first));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> after.open(first));
        assertFalse(new SecretBox("").needsRekey("anything"));
    }

    @Test
    void signatureIsHmacOverTimestampAndBody() {
        assertEquals("t=1700000000,v1=38877139021993b830af32feea6e18a8da83eb2f6e49ee50bd9e4cf4ca4d3789",
            WebhookSigner.header("whsec_test", 1700000000L, "{\"a\":1}"));
    }

    @Test
    void onlyPublicHttpsNamesAreAccepted() {
        assertNull(WebhookUrls.problem("https://hooks.example.com/schoomano"));
        assertNull(WebhookUrls.problem("https://hooks.example.com:8443/x"));
        assertNotNull(WebhookUrls.problem("http://hooks.example.com/x"));
        assertNotNull(WebhookUrls.problem("https://localhost/x"));
        assertNotNull(WebhookUrls.problem("https://10.0.0.5/x"));
        assertNotNull(WebhookUrls.problem("https://169.254.169.254/latest"));
        assertNotNull(WebhookUrls.problem("https://[::1]/x"));
        assertNotNull(WebhookUrls.problem("https://intranet/x"));
        assertNotNull(WebhookUrls.problem("https://printer.local/x"));
        assertNotNull(WebhookUrls.problem("https://user:pw@hooks.example.com/x"));
        assertNotNull(WebhookUrls.problem("https://hooks.example.com:22/x"));
        assertNotNull(WebhookUrls.problem("not a url"));
    }

    @Test
    void privateAddressesAreRefused() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fd00::1"}) {
            assertFalse(WebhookUrls.isPublic(InetAddress.getByName(ip)), ip);
        }
        assertTrue(WebhookUrls.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(WebhookUrls.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void postsSignedEventsAndNeverToPrivateAddressesByDefault() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> event = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        int[] reply = {204};
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("X-Schoomano-Signature"));
            event.set(exchange.getRequestHeaders().getFirst("X-Schoomano-Event"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(reply[0], -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            WebhookDispatcher local = new WebhookDispatcher(null, new SecretBox(""), new ObjectMapper(), false, true);
            WebhookDispatcher.Outcome ok = local.post(url, List.of("whsec_test"), UUID.randomUUID(), "config.published", "{\"a\":1}");
            assertEquals(204, ok.status());
            assertNull(ok.error());
            assertEquals("config.published", event.get());
            assertEquals("{\"a\":1}", body.get());
            long t = Long.parseLong(signature.get().split(",")[0].substring(2));
            assertEquals(WebhookSigner.header("whsec_test", t, "{\"a\":1}"), signature.get());

            reply[0] = 500;
            WebhookDispatcher.Outcome bad = local.post(url, List.of("whsec_test"), UUID.randomUUID(), "config.published", "{}");
            assertEquals(500, bad.status());
            assertEquals("http_500", bad.error());

            WebhookDispatcher strict = new WebhookDispatcher(null, new SecretBox(""), new ObjectMapper(), false, false);
            assertEquals("address_not_public", strict.post(url, List.of("whsec_test"), UUID.randomUUID(), "config.published", "{}").error());
        } finally {
            server.stop(0);
        }
    }
}
