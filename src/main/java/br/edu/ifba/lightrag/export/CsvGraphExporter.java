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
 * CSV exporter for knowledge graph data.
 * 
 * <p>Exports entities and relations to CSV format with proper escaping
 * and streaming support for memory efficiency.</p>
 * 
 * <h2>Output Format:</h2>
 * <pre>
 * === ENTITIES ===
 * entity_name,entity_type,description,source_chunk_count
 * "John Doe","PERSON","A software engineer",3
 * 
 * === RELATIONS ===
 * source,target,description,keywords,weight
 * "John Doe","ACME Corp","works at","employment, job",1.0
 * </pre>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class CsvGraphExporter implements GraphExporter {
    
    private static final String ENTITY_HEADER = "entity_name,entity_type,description,document_id,source_chunk_count";
    private static final String RELATION_HEADER = "source,target,description,keywords,weight,document_id,source_chunk_count";
    
    @Override
    public void export(
            @NotNull List<Entity> entities,
            @NotNull List<Relation> relations,
            @NotNull ExportConfig config,
            @NotNull OutputStream outputStream) throws IOException {
        
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            
            if (config.includeEntities() && !entities.isEmpty()) {
                writeEntities(writer, entities, config);
            }
            
            if (config.includeRelations() && !relations.isEmpty()) {
                if (config.includeEntities() && !entities.isEmpty()) {
                    writer.newLine();
                }
                writeRelations(writer, relations, config);
            }
            
            writer.flush();
        }
    }
    
    /**
     * Writes entities section to CSV.
     */
    private void writeEntities(BufferedWriter writer, List<Entity> entities, ExportConfig config) throws IOException {
        writer.write("# ENTITIES");
        writer.newLine();
        writer.write(ENTITY_HEADER);
        writer.newLine();
        
        int count = 0;
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        
        for (Entity entity : entities) {
            if (count >= maxItems) {
                break;
            }
            
            writer.write(escapeCsv(entity.getEntityName()));
            writer.write(",");
            writer.write(escapeCsv(entity.getEntityType() != null ? entity.getEntityType() : ""));
            writer.write(",");
            writer.write(escapeCsv(entity.getDescription()));
            writer.write(",");
            writer.write(escapeCsv(entity.getDocumentId() != null ? entity.getDocumentId() : ""));
            writer.write(",");
            writer.write(String.valueOf(entity.getSourceChunkIds().size()));
            writer.newLine();
            
            count++;
        }
    }
    
    /**
     * Writes relations section to CSV.
     */
    private void writeRelations(BufferedWriter writer, List<Relation> relations, ExportConfig config) throws IOException {
        writer.write("# RELATIONS");
        writer.newLine();
        writer.write(RELATION_HEADER);
        writer.newLine();
        
        int count = 0;
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        
        for (Relation relation : relations) {
            if (count >= maxItems) {
                break;
            }
            
            writer.write(escapeCsv(relation.getSrcId()));
            writer.write(",");
            writer.write(escapeCsv(relation.getTgtId()));
            writer.write(",");
            writer.write(escapeCsv(relation.getDescription()));
            writer.write(",");
            writer.write(escapeCsv(relation.getKeywords()));
            writer.write(",");
            writer.write(String.valueOf(relation.getWeight()));
            writer.write(",");
            writer.write(escapeCsv(relation.getDocumentId() != null ? relation.getDocumentId() : ""));
            writer.write(",");
            writer.write(String.valueOf(relation.getSourceChunkIds().size()));
            writer.newLine();
            
            count++;
        }
    }
    
    /**
     * Escapes a string for CSV output.
     * 
     * <p>RFC 4180 compliant: quotes fields containing commas, newlines, or quotes.
     * Doubles any embedded quotes.</p>
     * 
     * @param value The value to escape
     * @return CSV-safe string
     */
    private String escapeCsv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        
        boolean needsQuoting = value.contains(",") || 
                               value.contains("\"") || 
                               value.contains("\n") ||
                               value.contains("\r");
        
        if (needsQuoting) {
            // Double any existing quotes and wrap in quotes
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        
        return value;
    }
    
    @Override
    public String getMimeType() {
        return ExportConfig.ExportFormat.CSV.getMimeType();
    }
    
    @Override
    public String getFileExtension() {
        return ExportConfig.ExportFormat.CSV.getExtension();
    }
    
    @Override
    public ExportConfig.ExportFormat getFormat() {
        return ExportConfig.ExportFormat.CSV;
    }
}
