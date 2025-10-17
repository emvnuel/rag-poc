package br.edu.ifba;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

    /**
     * Chunks a given text into smaller strings of a specified maximum size.
     *
     * @param text The input text to be chunked.
     * @param chunkSize The maximum size of each chunk (in characters).
     * @return A List of strings, where each string is a chunk of the original text.
     */
    public static List<String> chunkText(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks; // Return empty list for null or empty input
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be a positive integer.");
        }

        int textLength = text.length();
        int start = 0;
        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);

            // If we're at the end, take the rest
            if (end == textLength) {
                chunks.add(text.substring(start, end));
                break;
            }

            // If the character at 'end' is whitespace, split there
            if (Character.isWhitespace(text.charAt(end))) {
                if (end > start) {
                    chunks.add(text.substring(start, end));
                }
                start = end + 1;
                while (start < textLength && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                continue;
            }

            // Search backwards for the last whitespace inside the chunk window
            int split = -1;
            for (int i = end - 1; i >= start; i--) {
                if (Character.isWhitespace(text.charAt(i))) {
                    split = i;
                    break;
                }
            }

            if (split > start) {
                chunks.add(text.substring(start, split));
                start = split + 1;
                while (start < textLength && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
            } else if (split == start) {
                // Leading whitespace at start, skip it
                start = split + 1;
                while (start < textLength && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
            } else {
                // No whitespace found within window — force split at chunk size
                chunks.add(text.substring(start, end));
                start = end;
            }
        }

        return chunks;
    }

}

