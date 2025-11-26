package br.edu.ifba.lightrag.export;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Plain text exporter for knowledge graph data.
 * 
 * <p>Exports entities and relations in a simple, human-readable text format
 * suitable for quick inspection or command-line viewing.</p>
 * 
 * <h2>Output Format:</h2>
 * <pre>
 * ===== KNOWLEDGE GRAPH EXPORT =====
 * 
 * ----- ENTITIES (2) -----
 * 
 * [1] John Doe
 *     Type: PERSON
 *     Description: A software engineer who specializes in distributed systems.
 *     Document: doc-123
 *     Sources: 3 chunks
 * 
 * [2] ACME Corp
 *     Type: ORGANIZATION
 *     Description: A technology company focused on innovation.
 *     Document: doc-123
 *     Sources: 2 chunks
 * 
 * ----- RELATIONS (1) -----
 * 
 * [1] John Doe -> ACME Corp
 *     Description: works at as a senior engineer
 *     Keywords: employment, job, work
 *     Weight: 1.00
 * </pre>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class TextGraphExporter implements GraphExporter {
    
    private static final String SEPARATOR = "=".repeat(50);
    private static final String SUB_SEPARATOR = "-".repeat(40);
    
    @Override
    public void export(
            @NotNull List<Entity> entities,
            @NotNull List<Relation> relations,
            @NotNull ExportConfig config,
            @NotNull OutputStream outputStream) throws IOException {
        
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            
            writer.write(SEPARATOR);
            writer.newLine();
            writer.write("KNOWLEDGE GRAPH EXPORT");
            writer.newLine();
            writer.write(SEPARATOR);
            writer.newLine();
            writer.newLine();
            
            if (config.includeEntities()) {
                writeEntities(writer, entities, config);
            }
            
            if (config.includeRelations()) {
                if (config.includeEntities() && !entities.isEmpty()) {
                    writer.newLine();
                }
                writeRelations(writer, relations, config);
            }
            
            writer.flush();
        }
    }
    
    /**
     * Writes entities section.
     */
    private void writeEntities(BufferedWriter writer, List<Entity> entities, 
                               ExportConfig config) throws IOException {
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        int count = Math.min(entities.size(), maxItems);
        
        writer.write(SUB_SEPARATOR);
        writer.newLine();
        writer.write("ENTITIES (" + count + ")");
        writer.newLine();
        writer.write(SUB_SEPARATOR);
        writer.newLine();
        
        if (entities.isEmpty()) {
            writer.newLine();
            writer.write("(no entities)");
            writer.newLine();
            return;
        }
        
        int index = 1;
        for (Entity entity : entities) {
            if (index > maxItems) {
                break;
            }
            
            writer.newLine();
            writer.write("[" + index + "] " + entity.getEntityName());
            writer.newLine();
            
            writer.write("    Type: " + (entity.getEntityType() != null ? entity.getEntityType() : "(none)"));
            writer.newLine();
            
            writer.write("    Description: " + formatDescription(entity.getDescription()));
            writer.newLine();
            
            if (entity.getDocumentId() != null) {
                writer.write("    Document: " + entity.getDocumentId());
                writer.newLine();
            }
            
            writer.write("    Sources: " + entity.getSourceChunkIds().size() + " chunks");
            writer.newLine();
            
            index++;
        }
    }
    
    /**
     * Writes relations section.
     */
    private void writeRelations(BufferedWriter writer, List<Relation> relations, 
                                ExportConfig config) throws IOException {
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        int count = Math.min(relations.size(), maxItems);
        
        writer.write(SUB_SEPARATOR);
        writer.newLine();
        writer.write("RELATIONS (" + count + ")");
        writer.newLine();
        writer.write(SUB_SEPARATOR);
        writer.newLine();
        
        if (relations.isEmpty()) {
            writer.newLine();
            writer.write("(no relations)");
            writer.newLine();
            return;
        }
        
        int index = 1;
        for (Relation relation : relations) {
            if (index > maxItems) {
                break;
            }
            
            writer.newLine();
            writer.write("[" + index + "] " + relation.getSrcId() + " -> " + relation.getTgtId());
            writer.newLine();
            
            writer.write("    Description: " + formatDescription(relation.getDescription()));
            writer.newLine();
            
            writer.write("    Keywords: " + relation.getKeywords());
            writer.newLine();
            
            writer.write("    Weight: " + String.format("%.2f", relation.getWeight()));
            writer.newLine();
            
            if (relation.getDocumentId() != null) {
                writer.write("    Document: " + relation.getDocumentId());
                writer.newLine();
            }
            
            writer.write("    Sources: " + relation.getSourceChunkIds().size() + " chunks");
            writer.newLine();
            
            index++;
        }
    }
    
    /**
     * Formats description for text output.
     * 
     * <p>Truncates long descriptions and normalizes whitespace.</p>
     * 
     * @param description The description to format
     * @return Formatted description string
     */
    private String formatDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "(none)";
        }
        
        // Normalize whitespace
        String normalized = description.replaceAll("\\s+", " ").trim();
        
        // Truncate if too long
        if (normalized.length() > 200) {
            return normalized.substring(0, 197) + "...";
        }
        
        return normalized;
    }
    
    @Override
    public String getMimeType() {
        return ExportConfig.ExportFormat.TEXT.getMimeType();
    }
    
    @Override
    public String getFileExtension() {
        return ExportConfig.ExportFormat.TEXT.getExtension();
    }
    
    @Override
    public ExportConfig.ExportFormat getFormat() {
        return ExportConfig.ExportFormat.TEXT;
    }
}
