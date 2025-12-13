package br.edu.ifba.lightrag.export;

import br.edu.ifba.lightrag.storage.impl.SQLiteConnectionManager;
import br.edu.ifba.lightrag.storage.impl.SQLiteExportService;
import br.edu.ifba.project.ProjectRepositoryPort;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * REST resource for SQLite portable knowledge base export/import.
 * 
 * <p>Enables users to:</p>
 * <ul>
 *   <li>Export a complete project to a standalone SQLite database file</li>
 *   <li>Import project data from a previously exported SQLite file</li>
 *   <li>Transfer knowledge bases between instances</li>
 *   <li>Create offline backups of project data</li>
 * </ul>
 * 
 * <h2>Endpoints:</h2>
 * <ul>
 *   <li>{@code GET /projects/{projectId}/export/sqlite} - Export project to SQLite file</li>
 *   <li>{@code POST /projects/{projectId}/import/sqlite} - Import project from SQLite file</li>
 * </ul>
 * 
 * <h2>Example Requests:</h2>
 * <pre>
 * # Export project to SQLite
 * curl -O http://localhost:8080/projects/123/export/sqlite
 * 
 * # Import from SQLite file
 * curl -X POST -H "Content-Type: application/x-sqlite3" \
 *      --data-binary @export.db \
 *      http://localhost:8080/projects/456/import/sqlite
 * </pre>
 * 
 * @since spec-009
 */
@Path("/projects/{projectId}")
@RequestScoped
public class SQLiteExportResources {
    
    private static final Logger LOG = Logger.getLogger(SQLiteExportResources.class);
    
    private static final String SQLITE3_MIME_TYPE = "application/x-sqlite3";
    
    @Inject
    ProjectRepositoryPort projectRepository;
    
    @ConfigProperty(name = "lightrag.storage.sqlite.path", defaultValue = "lightrag.db")
    String sqlitePath;
    
    @ConfigProperty(name = "lightrag.storage.backend", defaultValue = "postgresql")
    String storageBackend;
    
    /**
     * Exports a project to a standalone SQLite database file.
     * 
     * <p>The exported file contains:</p>
     * <ul>
     *   <li>All entities and relations for the project</li>
     *   <li>All vector embeddings</li>
     *   <li>Extraction cache entries</li>
     *   <li>Document metadata</li>
     * </ul>
     * 
     * <p>The file can be imported into another instance using the import endpoint.</p>
     * 
     * @param projectId The project UUID to export
     * @return SQLite database file as binary download
     */
    @GET
    @Path("/export/sqlite")
    @Produces(SQLITE3_MIME_TYPE)
    public Response exportToSqlite(@PathParam("projectId") String projectId) {
        LOG.infof("Exporting project %s to SQLite", projectId);
        
        // Validate SQLite backend is enabled
        if (!"sqlite".equalsIgnoreCase(storageBackend)) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                        "error", "SQLite backend not enabled",
                        "message", "SQLite export requires lightrag.storage.backend=sqlite"))
                    .build();
        }
        
        // Validate project exists
        if (!projectExists(projectId)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Project not found: " + projectId))
                    .build();
        }
        
        try {
            // Create temporary file for export
            java.nio.file.Path exportPath = Files.createTempFile("sqlite-export-", ".db");
            
            // Export project data
            try (SQLiteConnectionManager connectionManager = new SQLiteConnectionManager(sqlitePath)) {
                SQLiteExportService exportService = new SQLiteExportService(connectionManager);
                exportService.exportProject(projectId, exportPath).get();
            }
            
            // Read exported file for streaming
            byte[] exportedData = Files.readAllBytes(exportPath);
            
            // Clean up temp file
            Files.deleteIfExists(exportPath);
            
            // Build filename
            String filename = buildFilename(projectId);
            
            LOG.infof("Export completed for project %s, file size: %d bytes", projectId, exportedData.length);
            
            return Response.ok(exportedData)
                    .type(SQLITE3_MIME_TYPE)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Length", exportedData.length)
                    .build();
            
        } catch (ExecutionException e) {
            LOG.errorf(e, "Failed to export project %s: %s", projectId, e.getCause().getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Export failed", "message", e.getCause().getMessage()))
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "Export interrupted for project %s", projectId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Export interrupted"))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to export project %s: %s", projectId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Export failed", "message", e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Imports project data from a SQLite database file.
     * 
     * <p>The import process:</p>
     * <ol>
     *   <li>Validates the uploaded file is a valid SQLite database</li>
     *   <li>Extracts project data from the file</li>
     *   <li>Remaps data to the target project ID</li>
     *   <li>Inserts all data into the target project</li>
     * </ol>
     * 
     * @param projectId The target project UUID to import into
     * @param inputStream The SQLite file content
     * @return Import result with success status
     */
    @POST
    @Path("/import/sqlite")
    @Consumes(SQLITE3_MIME_TYPE)
    @Produces("application/json")
    public Response importFromSqlite(
            @PathParam("projectId") String projectId,
            InputStream inputStream) {
        
        LOG.infof("Importing SQLite data into project %s", projectId);
        
        // Validate SQLite backend is enabled
        if (!"sqlite".equalsIgnoreCase(storageBackend)) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                        "error", "SQLite backend not enabled",
                        "message", "SQLite import requires lightrag.storage.backend=sqlite"))
                    .build();
        }
        
        // Validate project exists
        if (!projectExists(projectId)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Project not found: " + projectId))
                    .build();
        }
        
        java.nio.file.Path importPath = null;
        
        try {
            // Write uploaded content to temp file
            importPath = Files.createTempFile("sqlite-import-", ".db");
            Files.copy(inputStream, importPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // Validate it's a SQLite file (check header)
            byte[] header = new byte[16];
            try (var fis = Files.newInputStream(importPath)) {
                int read = fis.read(header);
                if (read < 16 || !new String(header, 0, 15).equals("SQLite format 3")) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                "error", "Invalid file format",
                                "message", "File is not a valid SQLite database"))
                            .build();
                }
            }
            
            // Import project data
            try (SQLiteConnectionManager connectionManager = new SQLiteConnectionManager(sqlitePath)) {
                SQLiteExportService exportService = new SQLiteExportService(connectionManager);
                exportService.importProject(importPath, projectId).get();
            }
            
            LOG.infof("Import completed for project %s", projectId);
            
            return Response.ok(Map.of(
                    "imported", true,
                    "projectId", projectId,
                    "message", "Project data imported successfully"))
                    .build();
            
        } catch (ExecutionException e) {
            LOG.errorf(e, "Failed to import into project %s: %s", projectId, e.getCause().getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Import failed", "message", e.getCause().getMessage()))
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "Import interrupted for project %s", projectId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Import interrupted"))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to import into project %s: %s", projectId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Import failed", "message", e.getMessage()))
                    .build();
        } finally {
            // Clean up temp file
            if (importPath != null) {
                try {
                    Files.deleteIfExists(importPath);
                } catch (IOException ignored) {
                    // Ignore cleanup errors
                }
            }
        }
    }
    
    /**
     * Checks if a project exists.
     */
    private boolean projectExists(String projectId) {
        try {
            UUID uuid = UUID.fromString(projectId);
            return projectRepository.findProjectById(uuid).isPresent();
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
    private String buildFilename(String projectId) {
        String shortId = projectId.length() > 8 ? projectId.substring(0, 8) : projectId;
        return "project-" + shortId + "-export.db";
    }
}
