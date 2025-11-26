package br.edu.ifba.lightrag.export;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Configuration for knowledge graph export operations.
 * 
 * <h2>Supported Formats:</h2>
 * <ul>
 *   <li>{@code csv} - Comma-separated values with header row</li>
 *   <li>{@code excel} - Microsoft Excel format (XLSX) with streaming</li>
 *   <li>{@code markdown} - Markdown tables for documentation</li>
 *   <li>{@code text} - Plain text format for simple viewing</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * ExportConfig config = ExportConfig.builder()
 *     .format(ExportFormat.CSV)
 *     .includeEntities(true)
 *     .includeRelations(true)
 *     .batchSize(1000)
 *     .build();
 * }</pre>
 * 
 * @param format The export format (csv, excel, markdown, text)
 * @param includeEntities Whether to include entities in export
 * @param includeRelations Whether to include relations in export
 * @param batchSize Number of items to fetch per batch for streaming
 * @param maxItems Maximum number of items to export (null = unlimited)
 * @since spec-007
 */
public record ExportConfig(
    @NotNull ExportFormat format,
    boolean includeEntities,
    boolean includeRelations,
    int batchSize,
    @Nullable Integer maxItems
) {
    
    /**
     * Default batch size for streaming exports.
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;
    
    /**
     * Compact constructor with validation.
     */
    public ExportConfig {
        Objects.requireNonNull(format, "format must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, got: " + batchSize);
        }
        if (maxItems != null && maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be > 0 or null, got: " + maxItems);
        }
        if (!includeEntities && !includeRelations) {
            throw new IllegalArgumentException("At least one of includeEntities or includeRelations must be true");
        }
    }
    
    /**
     * Creates a default configuration for CSV export.
     * 
     * @return ExportConfig with CSV format and default settings
     */
    public static ExportConfig csvDefault() {
        return new ExportConfig(ExportFormat.CSV, true, true, DEFAULT_BATCH_SIZE, null);
    }
    
    /**
     * Creates a default configuration for the specified format.
     * 
     * @param format The export format
     * @return ExportConfig with specified format and default settings
     */
    public static ExportConfig defaultFor(@NotNull ExportFormat format) {
        return new ExportConfig(format, true, true, DEFAULT_BATCH_SIZE, null);
    }
    
    /**
     * Creates a new builder.
     * 
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Export formats.
     */
    public enum ExportFormat {
        CSV("text/csv", "csv"),
        EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        MARKDOWN("text/markdown", "md"),
        TEXT("text/plain", "txt");
        
        private final String mimeType;
        private final String extension;
        
        ExportFormat(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }
        
        public String getMimeType() {
            return mimeType;
        }
        
        public String getExtension() {
            return extension;
        }
        
        /**
         * Parses format from string, case-insensitive.
         * 
         * @param value The string value
         * @return Matching ExportFormat
         * @throws IllegalArgumentException if value doesn't match
         */
        public static ExportFormat fromString(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return CSV; // Default
            }
            
            try {
                return valueOf(value.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid export format: '" + value + "'. Valid values: csv, excel, markdown, text"
                );
            }
        }
    }
    
    /**
     * Builder for ExportConfig.
     */
    public static class Builder {
        private ExportFormat format = ExportFormat.CSV;
        private boolean includeEntities = true;
        private boolean includeRelations = true;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private Integer maxItems = null;
        
        public Builder format(@NotNull ExportFormat format) {
            this.format = format;
            return this;
        }
        
        public Builder format(@NotNull String format) {
            this.format = ExportFormat.fromString(format);
            return this;
        }
        
        public Builder includeEntities(boolean includeEntities) {
            this.includeEntities = includeEntities;
            return this;
        }
        
        public Builder includeRelations(boolean includeRelations) {
            this.includeRelations = includeRelations;
            return this;
        }
        
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }
        
        public Builder maxItems(@Nullable Integer maxItems) {
            this.maxItems = maxItems;
            return this;
        }
        
        public ExportConfig build() {
            return new ExportConfig(format, includeEntities, includeRelations, batchSize, maxItems);
        }
    }
}
