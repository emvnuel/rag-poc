package br.edu.ifba.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

public final class UuidUtils {

    private static final SecureRandom random = new SecureRandom();

    private UuidUtils() {
    }

    public static UUID randomV7() {
        final byte[] value = randomBytes();
        final ByteBuffer buf = ByteBuffer.wrap(value);
        final long high = buf.getLong();
        final long low = buf.getLong();
        return new UUID(high, low);
    }

    public static byte[] randomBytes() {
        final byte[] value = new byte[16];
        random.nextBytes(value);
        final ByteBuffer timestamp = ByteBuffer.allocate(Long.BYTES);
        timestamp.putLong(System.currentTimeMillis());
        System.arraycopy(timestamp.array(), 2, value, 0, 6);
        value[6] = (byte) ((value[6] & 0x0F) | 0x70);
        value[8] = (byte) ((value[8] & 0x3F) | 0x80);
        return value;
    }

    /**
     * Generates a deterministic UUID v5 (name-based) from an input string.
     * Same input always produces the same UUID, useful for entity deduplication.
     * Uses SHA-1 hashing with a namespace for entity vectors.
     *
     * @param input The input string to generate UUID from
     * @return Deterministic UUID
     */
    public static UUID deterministicV5(String input) {
        try {
            // Use a fixed namespace UUID for entity vectors (randomly generated, fixed in code)
            UUID namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
            
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(asBytes(namespace));
            md.update(input.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            
            // Use first 16 bytes of hash for UUID
            ByteBuffer buf = ByteBuffer.wrap(hash);
            long msb = buf.getLong();
            long lsb = buf.getLong();
            
            // Set version (5) and variant bits
            msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000005000L; // Version 5
            lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // Variant 10
            
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Converts UUID to byte array.
     */
    private static byte[] asBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}
