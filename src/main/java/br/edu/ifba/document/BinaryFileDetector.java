package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Detects binary files that should not be processed as code.
 * Uses multiple detection layers: extension blacklist, magic bytes, and NUL byte analysis.
 */
@ApplicationScoped
public class BinaryFileDetector {
    
    /**
     * Binary file extensions that should be rejected.
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        // Compiled Python
        ".pyc", ".pyo",
        // Java compiled/archives
        ".class", ".jar", ".war", ".ear",
        // Native libraries
        ".so", ".dll", ".dylib",
        // Executables and binaries
        ".exe", ".bin", ".o", ".a", ".lib",
        // Archives
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar",
        // Images
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
        // Documents
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        // Media
        ".mp3", ".mp4", ".avi", ".mov", ".wav", ".ogg"
    );
    
    /**
     * Magic byte signatures for common binary formats.
     */
    private static final byte[][] MAGIC_BYTES = {
        {0x7F, 0x45, 0x4C, 0x46},                    // ELF binary
        {0x4D, 0x5A},                                 // Windows executable (MZ)
        {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE},  // Java .class
        {0x50, 0x4B, 0x03, 0x04},                    // ZIP/JAR archive
        {(byte) 0x89, 0x50, 0x4E, 0x47}              // PNG image
    };
    
    /**
     * NUL byte threshold - files with more NUL bytes are likely binary.
     */
    private static final int NUL_BYTE_THRESHOLD = 10;
    
    /**
     * Check if file is binary based on extension.
     *
     * @param fileName the file name to check
     * @return true if the extension indicates a binary file
     */
    public boolean isBinaryExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        
        String lowerFileName = fileName.toLowerCase();
        
        // Check if any binary extension matches
        return BINARY_EXTENSIONS.stream()
            .anyMatch(lowerFileName::endsWith);
    }
    
    /**
     * Check if content appears to be binary.
     * Examines magic bytes and NUL byte frequency.
     *
     * @param header the first bytes of the file content
     * @return true if the content appears to be binary
     */
    public boolean isBinaryContent(byte[] header) {
        if (header == null || header.length == 0) {
            return false;
        }
        
        // Check magic bytes
        if (matchesMagicBytes(header)) {
            return true;
        }
        
        // Check NUL byte frequency
        return hasHighNulByteFrequency(header);
    }
    
    /**
     * Check if content starts with known binary magic bytes.
     *
     * @param content the file content
     * @return true if magic bytes match a binary format
     */
    private boolean matchesMagicBytes(byte[] content) {
        for (byte[] magic : MAGIC_BYTES) {
            if (startsWith(content, magic)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if content starts with the given byte sequence.
     *
     * @param content the content to check
     * @param prefix the prefix to match
     * @return true if content starts with prefix
     */
    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Check if content has high NUL byte frequency (likely binary).
     *
     * @param content the file content
     * @return true if NUL byte count exceeds threshold
     */
    private boolean hasHighNulByteFrequency(byte[] content) {
        int nulCount = 0;
        
        for (byte b : content) {
            if (b == 0x00) {
                nulCount++;
                if (nulCount > NUL_BYTE_THRESHOLD) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Combined check for binary file detection.
     *
     * @param fileName the file name
     * @param header the first bytes of the file content
     * @return true if the file is binary
     */
    public boolean isBinary(String fileName, byte[] header) {
        return isBinaryExtension(fileName) || isBinaryContent(header);
    }
}
