package br.edu.ifba;

public final class TextFormatter {

    private TextFormatter() {
    }

    public static String format(final String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        
        return text.replaceAll("_{2,}", "")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
}
