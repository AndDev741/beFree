package org.beFree.whatsapp;

import java.util.regex.Pattern;

/**
 * Models keep emitting Markdown no matter what the prompt says; WhatsApp only
 * renders *bold*, _italic_, ~strike~ and ```mono```. Translate the common
 * Markdown habits into what WhatsApp shows, deterministically.
 */
public final class WhatsAppText {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    // [ \t] on purpose: \s would also match the newline of the previous (blank) line
    private static final Pattern HEADER = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern BULLET = Pattern.compile("(?m)^([ \\t]*)[-*][ \\t]+(?=\\S)");
    private static final Pattern FENCE = Pattern.compile("(?m)^[ \\t]*```[^\\n]*\\n?");
    private static final Pattern MANY_BLANKS = Pattern.compile("\\n{3,}");

    private WhatsAppText() {
    }

    public static String format(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = FENCE.matcher(text).replaceAll("");
        out = HEADER.matcher(out).replaceAll("*$1*");
        out = BOLD.matcher(out).replaceAll("*$1*");
        out = BULLET.matcher(out).replaceAll("$1• ");
        out = MANY_BLANKS.matcher(out).replaceAll("\n\n");
        return out.trim();
    }
}
