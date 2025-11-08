package br.edu.ifba.lightrag.utils;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Utility class for handling embedding format conversions.
 * Supports both base64-encoded binary format and raw float arrays.
 */
public final class EmbeddingUtil {
    
    private EmbeddingUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Converts a float array to base64-encoded bytes.
     * Used for compact storage in databases.
     *
     * @param embedding The float array
     * @return Base64-encoded string
     */
    @NotNull
    public static String toBase64(@NotNull float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }
    
    /**
     * Converts a base64-encoded string back to a float array.
     * Used when reading embeddings from storage.
     *
     * @param base64 The base64-encoded string
     * @return Float array
     */
    @NotNull
    public static float[] fromBase64(@NotNull String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        float[] embedding = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }
    
    /**
     * Computes cosine similarity between two embeddings.
     * Returns a value between -1 (opposite) and 1 (identical).
     *
     * @param a First embedding
     * @param b Second embedding
     * @return Cosine similarity score
     */
    public static double cosineSimilarity(@NotNull float[] a, @NotNull float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Embeddings must have same dimensions: " + a.length + " vs " + b.length
            );
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    /**
     * Computes Euclidean distance between two embeddings.
     *
     * @param a First embedding
     * @param b Second embedding
     * @return Euclidean distance
     */
    public static double euclideanDistance(@NotNull float[] a, @NotNull float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Embeddings must have same dimensions: " + a.length + " vs " + b.length
            );
        }
        
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        
        return Math.sqrt(sum);
    }
}
