package br.edu.ifba.lightrag.export;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Interface for exporting knowledge graph data to various formats.
 * 
 * <p>Implementations should support streaming export for large graphs
 * by processing entities and relations in batches.</p>
 * 
 * <h2>Contract:</h2>
 * <ul>
 *   <li>MUST write valid format output to the stream</li>
 *   <li>MUST support partial exports (entities only, relations only)</li>
 *   <li>MUST NOT close the output stream (caller responsibility)</li>
 *   <li>SHOULD support streaming for memory efficiency</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * GraphExporter exporter = factory.getExporter(ExportFormat.CSV);
 * 
 * try (OutputStream os = response.getOutputStream()) {
 *     exporter.export(entities, relations, config, os);
 * }
 * }</pre>
 * 
 * @see ExportConfig
 * @see GraphExporterFactory
 * @since spec-007
 */
public interface GraphExporter {
    
    /**
     * Exports entities and relations to the output stream.
     * 
     * @param entities List of entities to export (may be empty)
     * @param relations List of relations to export (may be empty)
     * @param config Export configuration
     * @param outputStream Stream to write the export data
     * @throws IOException If writing fails
     */
    void export(
        @NotNull List<Entity> entities,
        @NotNull List<Relation> relations,
        @NotNull ExportConfig config,
        @NotNull OutputStream outputStream
    ) throws IOException;
    
    /**
     * Gets the MIME type for this exporter's output.
     * 
     * @return MIME type string (e.g., "text/csv")
     */
    String getMimeType();
    
    /**
     * Gets the file extension for this exporter's output.
     * 
     * @return File extension without dot (e.g., "csv")
     */
    String getFileExtension();
    
    /**
     * Gets the export format this exporter handles.
     * 
     * @return The export format
     */
    ExportConfig.ExportFormat getFormat();
}
