package br.edu.ifba.lightrag.export;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Excel exporter for knowledge graph data using Apache POI SXSSFWorkbook.
 * 
 * <p>Uses streaming workbook (SXSSFWorkbook) for memory-efficient export
 * of large knowledge graphs. Data is flushed to disk in batches.</p>
 * 
 * <h2>Output Format:</h2>
 * <ul>
 *   <li>Sheet "Entities": entity_name, entity_type, description, document_id, source_chunk_count</li>
 *   <li>Sheet "Relations": source, target, description, keywords, weight, document_id, source_chunk_count</li>
 * </ul>
 * 
 * @since spec-007
 */
@ApplicationScoped
public class ExcelGraphExporter implements GraphExporter {
    
    /**
     * Number of rows to keep in memory before flushing to disk.
     * SXSSFWorkbook default is 100, we use a higher value for better performance.
     */
    private static final int ROW_ACCESS_WINDOW_SIZE = 1000;
    
    @Override
    public void export(
            @NotNull List<Entity> entities,
            @NotNull List<Relation> relations,
            @NotNull ExportConfig config,
            @NotNull OutputStream outputStream) throws IOException {
        
        // SXSSFWorkbook is not auto-closeable in the traditional sense
        // We need to dispose of temp files manually
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW_SIZE);
        
        try {
            CellStyle headerStyle = createHeaderStyle(workbook);
            
            if (config.includeEntities()) {
                createEntitiesSheet(workbook, entities, config, headerStyle);
            }
            
            if (config.includeRelations()) {
                createRelationsSheet(workbook, relations, config, headerStyle);
            }
            
            workbook.write(outputStream);
            outputStream.flush();
        } finally {
            // Dispose of temporary files created by SXSSFWorkbook
            workbook.dispose();
            workbook.close();
        }
    }
    
    /**
     * Creates a bold header style.
     */
    private CellStyle createHeaderStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
    
    /**
     * Creates the Entities sheet.
     */
    private void createEntitiesSheet(SXSSFWorkbook workbook, List<Entity> entities, 
                                     ExportConfig config, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Entities");
        
        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"entity_name", "entity_type", "description", "document_id", "source_chunk_count"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        
        // Data rows
        int rowNum = 1;
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        
        for (Entity entity : entities) {
            if (rowNum > maxItems) {
                break;
            }
            
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(entity.getEntityName());
            row.createCell(1).setCellValue(entity.getEntityType() != null ? entity.getEntityType() : "");
            row.createCell(2).setCellValue(truncateForExcel(entity.getDescription()));
            row.createCell(3).setCellValue(entity.getDocumentId() != null ? entity.getDocumentId() : "");
            row.createCell(4).setCellValue(entity.getSourceChunkIds().size());
        }
    }
    
    /**
     * Creates the Relations sheet.
     */
    private void createRelationsSheet(SXSSFWorkbook workbook, List<Relation> relations, 
                                      ExportConfig config, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Relations");
        
        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"source", "target", "description", "keywords", "weight", "document_id", "source_chunk_count"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        
        // Data rows
        int rowNum = 1;
        int maxItems = config.maxItems() != null ? config.maxItems() : Integer.MAX_VALUE;
        
        for (Relation relation : relations) {
            if (rowNum > maxItems) {
                break;
            }
            
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(relation.getSrcId());
            row.createCell(1).setCellValue(relation.getTgtId());
            row.createCell(2).setCellValue(truncateForExcel(relation.getDescription()));
            row.createCell(3).setCellValue(relation.getKeywords());
            row.createCell(4).setCellValue(relation.getWeight());
            row.createCell(5).setCellValue(relation.getDocumentId() != null ? relation.getDocumentId() : "");
            row.createCell(6).setCellValue(relation.getSourceChunkIds().size());
        }
    }
    
    /**
     * Truncates text to Excel's maximum cell content length (32767 characters).
     * 
     * @param text The text to truncate
     * @return Truncated text safe for Excel cells
     */
    private String truncateForExcel(String text) {
        if (text == null) {
            return "";
        }
        // Excel cell limit is 32767 characters
        if (text.length() > 32767) {
            return text.substring(0, 32764) + "...";
        }
        return text;
    }
    
    @Override
    public String getMimeType() {
        return ExportConfig.ExportFormat.EXCEL.getMimeType();
    }
    
    @Override
    public String getFileExtension() {
        return ExportConfig.ExportFormat.EXCEL.getExtension();
    }
    
    @Override
    public ExportConfig.ExportFormat getFormat() {
        return ExportConfig.ExportFormat.EXCEL;
    }
}
