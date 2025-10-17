package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.resteasy.reactive.multipart.FileUpload;

public final class FileMetadataExtractor {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private FileMetadataExtractor() {
    }

    public static String extractMetadata(final FileUpload file, final Map<String, Object> contentMetadata) {
        final Map<String, Object> metadata = new HashMap<>();

        try {
            final Path filePath = file.uploadedFile();
            
            metadata.put("originalFileName", file.fileName());
            metadata.put("contentType", file.contentType());
            metadata.put("size", Files.size(filePath));
            metadata.put("uploadedFileName", filePath.getFileName().toString());
            metadata.put("fileExtension", extractFileExtension(file.fileName()));
            metadata.put("uploadTimestamp", ISO_FORMATTER.format(Instant.now()));
            metadata.put("checksum", calculateChecksum(filePath));
            
            if (contentMetadata != null && !contentMetadata.isEmpty()) {
                metadata.putAll(contentMetadata);
            }

            return objectMapper.writeValueAsString(metadata);
        } catch (IOException e) {
            return "{}";
        }
    }

    private static String extractFileExtension(final String fileName) {
        final int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }

    private static String calculateChecksum(final Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            
            final byte[] hashBytes = digest.digest();
            final StringBuilder hexString = new StringBuilder();
            
            for (byte b : hashBytes) {
                final String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
