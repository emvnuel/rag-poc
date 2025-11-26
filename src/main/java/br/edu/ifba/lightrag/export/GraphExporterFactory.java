package br.edu.ifba.lightrag.export;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory for selecting the appropriate GraphExporter based on format.
 * 
 * <p>Uses CDI to discover all available GraphExporter implementations
 * and provides the correct one based on the requested format.</p>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * @Inject
 * GraphExporterFactory factory;
 * 
 * GraphExporter exporter = factory.getExporter(ExportFormat.CSV);
 * exporter.export(entities, relations, config, outputStream);
 * }</pre>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class GraphExporterFactory {
    
    private final Map<ExportConfig.ExportFormat, GraphExporter> exporters;
    
    /**
     * Default constructor for CDI proxy.
     */
    public GraphExporterFactory() {
        this.exporters = new EnumMap<>(ExportConfig.ExportFormat.class);
    }
    
    /**
     * Constructs the factory with CDI-discovered exporters.
     * 
     * @param exporterInstances All GraphExporter implementations
     */
    @Inject
    public GraphExporterFactory(Instance<GraphExporter> exporterInstances) {
        this.exporters = new EnumMap<>(ExportConfig.ExportFormat.class);
        
        for (GraphExporter exporter : exporterInstances) {
            exporters.put(exporter.getFormat(), exporter);
        }
    }
    
    /**
     * Gets the exporter for the specified format.
     * 
     * @param format The export format
     * @return GraphExporter implementation
     * @throws IllegalArgumentException if no exporter is registered for the format
     */
    @NotNull
    public GraphExporter getExporter(@NotNull ExportConfig.ExportFormat format) {
        GraphExporter exporter = exporters.get(format);
        
        if (exporter == null) {
            throw new IllegalArgumentException(
                    "No exporter registered for format: " + format + 
                    ". Available formats: " + exporters.keySet());
        }
        
        return exporter;
    }
    
    /**
     * Gets the exporter for the specified config.
     * 
     * @param config The export configuration
     * @return GraphExporter implementation
     */
    @NotNull
    public GraphExporter getExporter(@NotNull ExportConfig config) {
        return getExporter(config.format());
    }
    
    /**
     * Checks if an exporter is available for the specified format.
     * 
     * @param format The export format
     * @return true if an exporter is registered
     */
    public boolean hasExporter(@NotNull ExportConfig.ExportFormat format) {
        return exporters.containsKey(format);
    }
    
    /**
     * Gets the MIME type for the specified format.
     * 
     * @param format The export format
     * @return MIME type string
     */
    @NotNull
    public String getMimeType(@NotNull ExportConfig.ExportFormat format) {
        return getExporter(format).getMimeType();
    }
    
    /**
     * Gets the file extension for the specified format.
     * 
     * @param format The export format
     * @return File extension without dot
     */
    @NotNull
    public String getFileExtension(@NotNull ExportConfig.ExportFormat format) {
        return getExporter(format).getFileExtension();
    }
}
