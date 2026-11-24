package com.schoomano.domain.notice;

/**
 * Text of the SMS that accompanies a notice. SMS is readable on a locked phone and by anyone holding it, so it
 * never carries a child's name or the content of an attendance, health or fee message; staff announcements may
 * show their (short, staff-written) title.
 */
public final class SmsText {

    public static final int MAX = 160;
    private static final String SUFFIX = " Open Schoomano for details.";

    private SmsText() { }

    public static String forAnnouncement(String title) {
        String clean = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("a notice needs a title");
        }
        String head = "Schoomano: ";
        int room = MAX - head.length() - 1 - SUFFIX.length();
        if (clean.length() > room) {
            clean = clean.substring(0, room - 3).trim() + "...";
        }
        return head + clean + "." + SUFFIX;
    }
}
