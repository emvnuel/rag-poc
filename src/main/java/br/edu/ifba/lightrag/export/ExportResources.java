package br.edu.ifba.lightrag.export;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.project.ProjectRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * REST resource for exporting knowledge graph data.
 * 
 * <h2>Endpoints:</h2>
 * <ul>
 *   <li>{@code GET /projects/{projectId}/export} - Export knowledge graph</li>
 * </ul>
 * 
 * <h2>Query Parameters:</h2>
 * <ul>
 *   <li>{@code format} - Export format: csv, excel, markdown, text (default: csv)</li>
 *   <li>{@code entities} - Include entities (default: true)</li>
 *   <li>{@code relations} - Include relations (default: true)</li>
 *   <li>{@code maxItems} - Maximum items per type (optional, unlimited if not set)</li>
 * </ul>
 * 
 * <h2>Example Requests:</h2>
 * <pre>
 * GET /projects/123/export
 * GET /projects/123/export?format=excel
 * GET /projects/123/export?format=markdown&entities=true&relations=false
 * GET /projects/123/export?format=csv&maxItems=1000
 * </pre>
 * 
 * @since spec-007
 */
@Path("/projects/{projectId}/export")
public class ExportResources {
    
    private static final Logger LOG = Logger.getLogger(ExportResources.class);
    
    @Inject
    GraphStorage graphStorage;
    
    @Inject
    GraphExporterFactory exporterFactory;
    
    @Inject
    ProjectRepository projectRepository;
    
    /**
     * Exports the knowledge graph for a project.
     * 
     * @param projectId The project UUID
     * @param format Export format (csv, excel, markdown, text)
     * @param includeEntities Whether to include entities
     * @param includeRelations Whether to include relations
     * @param maxItems Maximum items per type (null = unlimited)
     * @return Streaming response with exported data
     */
    @GET
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
               "text/markdown", "text/plain"})
    public Response exportGraph(
            @PathParam("projectId") @NotNull String projectId,
            @QueryParam("format") @DefaultValue("csv") String format,
            @QueryParam("entities") @DefaultValue("true") boolean includeEntities,
            @QueryParam("relations") @DefaultValue("true") boolean includeRelations,
            @QueryParam("maxItems") Integer maxItems) {
        
        LOG.infof("Exporting knowledge graph for project %s, format=%s, entities=%s, relations=%s, maxItems=%s",
                projectId, format, includeEntities, includeRelations, maxItems);
        
        // Validate project exists
        if (!projectExists(projectId)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Project not found: " + projectId)
                    .build();
        }
        
        // Validate at least one type is requested
        if (!includeEntities && !includeRelations) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("At least one of 'entities' or 'relations' must be true")
                    .build();
        }
        
        // Parse and validate format
        ExportConfig.ExportFormat exportFormat;
        try {
            exportFormat = ExportConfig.ExportFormat.fromString(format);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(e.getMessage())
                    .build();
        }
        
        // Build config
        ExportConfig config = ExportConfig.builder()
                .format(exportFormat)
                .includeEntities(includeEntities)
                .includeRelations(includeRelations)
                .maxItems(maxItems)
                .build();
        
        // Get exporter
        GraphExporter exporter = exporterFactory.getExporter(config);
        
        // Create streaming output
        StreamingOutput streamingOutput = outputStream -> {
            try {
                List<Entity> entities = Collections.emptyList();
                List<Relation> relations = Collections.emptyList();
                
                if (config.includeEntities()) {
                    entities = graphStorage.getAllEntities(projectId).get();
                    LOG.debugf("Fetched %d entities for export", entities.size());
                }
                
                if (config.includeRelations()) {
                    relations = graphStorage.getAllRelations(projectId).get();
                    LOG.debugf("Fetched %d relations for export", relations.size());
                }
                
                exporter.export(entities, relations, config, outputStream);
                
                LOG.infof("Export completed for project %s: %d entities, %d relations",
                        projectId, entities.size(), relations.size());
                
            } catch (ExecutionException e) {
                LOG.errorf(e, "Failed to fetch data for export: %s", e.getMessage());
                throw new RuntimeException("Failed to fetch data for export", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.errorf(e, "Export interrupted: %s", e.getMessage());
                throw new RuntimeException("Export interrupted", e);
            }
        };
        
        // Build filename
        String filename = buildFilename(projectId, exporter.getFileExtension());
        
        return Response.ok(streamingOutput)
                .type(exporter.getMimeType())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }
    
    /**
     * Checks if a project exists.
     */
    private boolean projectExists(String projectId) {
        try {
            UUID uuid = UUID.fromString(projectId);
            return projectRepository.findByIdOptional(uuid).isPresent();
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid project ID format: %s", projectId);
            return false;
        } catch (Exception e) {
            LOG.warnf("Error checking project existence: %s", e.getMessage());
            return false;
        }
    }
    
    /**
     * Builds the export filename.
     */
    private String buildFilename(String projectId, String extension) {
        // Use first 8 chars of project ID for readability
        String shortId = projectId.length() > 8 ? projectId.substring(0, 8) : projectId;
        return "kg-export-" + shortId + "." + extension;
    }
}
