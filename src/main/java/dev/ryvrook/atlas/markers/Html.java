package dev.ryvrook.atlas.markers;

/** HTML escaping for popups that are not trusted. */
public final class Html {
    private Html() {
    }

    /** Escapes the five HTML special characters and turns line breaks into br tags. Null stays null. */
    public static String escape(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                case '\r' -> {
                }
                case '\n' -> b.append("<br>");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
