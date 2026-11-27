package com.schoomano.platform;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Which addresses a webhook may point at. Only https on the usual ports, never a bare IP address, a name that stays
 * inside a network, or an address in a private range, so a school cannot aim the server at its own internal network.
 */
public final class WebhookUrls {

    private WebhookUrls() { }

    /** The problem with this address, or null when it is acceptable to register. */
    public static String problem(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (URISyntaxException e) {
            return "That is not a valid web address.";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return "The address must start with https://.";
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return "The address cannot contain a user name, password or fragment.";
        }
        if (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 8443) {
            return "Use port 443 or 8443.";
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean literal = host.startsWith("[") || host.matches("[0-9.]+");
        if (literal || !host.contains(".") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")
            || host.equals("localhost")) {
            return "Use a public host name, not an IP address or an internal name.";
        }
        return null;
    }

    /** True for an address on the public internet. */
    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return !(first == 0 || (first == 100 && second >= 64 && second <= 127) || (first == 192 && second == 0) || (first == 198 && (second == 18 || second == 19)) || first >= 240);
        }
        return (b[0] & 0xfe) != 0xfc;   // fc00::/7 unique local addresses
    }
}
