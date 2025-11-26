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
 * Markdown exporter for knowledge graph data.
 * 
 * <p>Exports entities and relations as Markdown tables suitable for
 * documentation, GitHub wikis, or other Markdown-compatible systems.</p>
 * 
 * <h2>Output Format:</h2>
 * <pre>
 * # Knowledge Graph Export
 * 
 * ## Entities (2)
 * 
 * | Name | Type | Description |
 * |------|------|-------------|
 * | John Doe | PERSON | A software engineer |
 * | ACME Corp | ORGANIZATION | A tech company |
 * 
 * ## Relations (1)
 * 
 * | Source | Target | Description | Keywords | Weight |
 * |--------|--------|-------------|----------|--------|
 * | John Doe | ACME Corp | works at | employment | 1.0 |
 * </pre>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class MarkdownGraphExporter implements GraphExporter {
    
    @Override
    public void export(
            @NotNull List<Entity> entities,
            @NotNull List<Relation> relations,
            @NotNull ExportConfig config,
            @NotNull OutputStream outputStream) throws IOException {
        
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            
            writer.write("# Knowledge Graph Export");
            writer.newLine();
            writer.newLine();
            
            if (config.includeEntities() && !entities.isEmpty()) {
                writeEntitiesTable(writer, entities, config);
            }
            
            if (config.includeRelations() && !relations.isEmpty()) {
                if (config.includeEntities() && !entities.isEmpty()) {
                    writer.newLine();
                }
                writeRelationsTable(writer, relations, config);
            }
            
            writer.flush();
        }
    }
    
    /**
     * Writes entities as a Markdown table.
     */
    private void writeEntitiesTable(BufferedWriter writer, List<Entity> entities, 
                                    ExportConfig config) throws IOException {
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        int count = Math.min(entities.size(), maxItems);
        
        writer.write("## Entities (" + count + ")");
        writer.newLine();
        writer.newLine();
        
        // Header
        writer.write("| Name | Type | Description | Document ID | Sources |");
        writer.newLine();
        writer.write("|------|------|-------------|-------------|---------|");
        writer.newLine();
        
        int written = 0;
        for (Entity entity : entities) {
            if (written >= maxItems) {
                break;
            }
            
            writer.write("| ");
            writer.write(escapeMarkdown(entity.getEntityName()));
            writer.write(" | ");
            writer.write(escapeMarkdown(entity.getEntityType() != null ? entity.getEntityType() : "-"));
            writer.write(" | ");
            writer.write(escapeMarkdown(truncateDescription(entity.getDescription())));
            writer.write(" | ");
            writer.write(escapeMarkdown(entity.getDocumentId() != null ? entity.getDocumentId() : "-"));
            writer.write(" | ");
            writer.write(String.valueOf(entity.getSourceChunkIds().size()));
            writer.write(" |");
            writer.newLine();
            
            written++;
        }
    }
    
    /**
     * Writes relations as a Markdown table.
     */
    private void writeRelationsTable(BufferedWriter writer, List<Relation> relations, 
                                     ExportConfig config) throws IOException {
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        int count = Math.min(relations.size(), maxItems);
        
        writer.write("## Relations (" + count + ")");
        writer.newLine();
        writer.newLine();
        
        // Header
        writer.write("| Source | Target | Description | Keywords | Weight |");
        writer.newLine();
        writer.write("|--------|--------|-------------|----------|--------|");
        writer.newLine();
        
        int written = 0;
        for (Relation relation : relations) {
            if (written >= maxItems) {
                break;
            }
            
            writer.write("| ");
            writer.write(escapeMarkdown(relation.getSrcId()));
            writer.write(" | ");
            writer.write(escapeMarkdown(relation.getTgtId()));
            writer.write(" | ");
            writer.write(escapeMarkdown(truncateDescription(relation.getDescription())));
            writer.write(" | ");
            writer.write(escapeMarkdown(relation.getKeywords()));
            writer.write(" | ");
            writer.write(String.format("%.2f", relation.getWeight()));
            writer.write(" |");
            writer.newLine();
            
            written++;
        }
    }
    
    /**
     * Escapes text for Markdown table cells.
     * 
     * <p>Replaces pipes and newlines which would break table formatting.</p>
     * 
     * @param text The text to escape
     * @return Markdown-safe string
     */
    private String escapeMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        return text
                .replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", "");
    }
    
    /**
     * Truncates description to reasonable length for table display.
     * 
     * @param description The description to truncate
     * @return Truncated description (max 100 chars)
     */
    private String truncateDescription(String description) {
        if (description == null) {
            return "";
        }
        if (description.length() > 100) {
            return description.substring(0, 97) + "...";
        }
        return description;
    }
    
    @Override
    public String getMimeType() {
        return ExportConfig.ExportFormat.MARKDOWN.getMimeType();
    }
    
    @Override
    public String getFileExtension() {
        return ExportConfig.ExportFormat.MARKDOWN.getExtension();
    }
    
    @Override
    public ExportConfig.ExportFormat getFormat() {
        return ExportConfig.ExportFormat.MARKDOWN;
    }
}
