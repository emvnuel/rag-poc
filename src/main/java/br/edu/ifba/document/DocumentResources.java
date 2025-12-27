package br.edu.ifba.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import br.edu.ifba.exception.FileUploadException;
import br.edu.ifba.exception.PdfProcessingException;
import br.edu.ifba.project.ProjectServicePort;
import br.edu.ifba.security.ProjectAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/documents")
@RolesAllowed({ "user", "admin" })
public class DocumentResources {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    DocumentServicePort documentService;

    @Inject
    ProjectServicePort projectService;

    @Inject
    DocumentExtractorFactory extractorFactory;

    @Inject
    SearchService searchService;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ProjectAuthorizationService authService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/files")
    public Response upload(@FormParam("file") FileUpload file, @FormParam("projectId") UUID projectId) {
        if (file == null) {
            throw new FileUploadException("No file uploaded");
        }

        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required");
        }

        // T035: Check authorization before uploading to project
        authService.checkWriteAccess(projectId);

        final String fileName = file.fileName().toLowerCase();

        try (InputStream textStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream();
                InputStream metadataStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream()) {

            final DocumentExtractor extractor = extractorFactory.getExtractor(fileName);
            final String text = extractor.extract(textStream);
            final Map<String, Object> contentMetadata = extractor.extractMetadata(metadataStream);
            final String formattedText = TextFormatter.format(text);
            final String metadata = FileMetadataExtractor.extractMetadata(file, contentMetadata);

            // Determine document type based on extractor
            final DocumentType documentType = (extractor instanceof CodeDocumentExtractor)
                    ? DocumentType.CODE
                    : DocumentType.FILE;

            final var project = projectService.findById(projectId);
            final Document document = new Document(documentType, fileName, formattedText, metadata, project);
            final Document created = documentService.create(document);

            return Response.created(URI.create("/documents/" + created.getId()))
                    .entity(new DocumentCreatedResponse(created.getId()))
                    .build();
        } catch (IOException e) {
            throw new PdfProcessingException("Error processing document: " + e.getMessage(), e);
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/texts")
    public Response processText(@Valid final TextRequest request) {

        final String formattedText = TextFormatter.format(request.text());

        final var project = projectService.findById(request.projectId());

        // Use documentType from request, default to TEXT if not specified
        final DocumentType docType = request.documentType() != null ? request.documentType() : DocumentType.TEXT;
        final String fileName = request.filename() != null ? request.filename() : "text-input";

        final Document document = new Document(docType, fileName, formattedText, null, project);
        final Document created = documentService.create(document);

        return Response.created(URI.create("/documents/" + created.getId()))
                .entity(new DocumentCreatedResponse(created.getId()))
                .build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/websites")
    public Response processWebsite(@Valid final WebsiteRequest request) {
        if (request.url() == null || request.url().isEmpty()) {
            throw new IllegalArgumentException("URL is required");
        }

        if (request.projectId() == null) {
            throw new IllegalArgumentException("Project ID is required");
        }

        try {
            final WebsiteDocumentExtractor extractor = new WebsiteDocumentExtractor();
            final String text = extractor.fetchAndExtract(request.url());
            final Map<String, Object> contentMetadata = extractor.fetchAndExtractMetadata(request.url());
            final String formattedText = TextFormatter.format(text);

            final String metadata = objectMapper.writeValueAsString(contentMetadata);

            final var project = projectService.findById(request.projectId());
            final Document document = new Document(DocumentType.WEBSITE, request.url(), formattedText, metadata,
                    project);
            final Document created = documentService.create(document);

            return Response.created(URI.create("/documents/" + created.getId()))
                    .entity(new DocumentCreatedResponse(created.getId()))
                    .build();
        } catch (IOException e) {
            throw new PdfProcessingException("Error processing website: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfProcessingException("Website processing interrupted: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public DocumentInfoResponse getById(@PathParam("id") final UUID id) {
        final Document document = documentService.findById(id);
        return new DocumentInfoResponse(
                document.getId(),
                document.getType(),
                document.getStatus(),
                document.getFileName(),
                document.getMetadata(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    @GET
    @Path("/{id}/progress")
    @Produces(MediaType.APPLICATION_JSON)
    public DocumentProgressResponse getProgress(@PathParam("id") final UUID id) {
        return documentService.getProcessingProgress(id);
    }

    @POST
    @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SearchResponse search(@Valid final SearchRequest request) {
        return searchService.search(request.query(), request.projectId());
    }

    @GET
    @Path("/{id}/content")
    @Produces(MediaType.TEXT_PLAIN)
    public String getContent(@PathParam("id") final UUID id) {
        final Document document = documentService.findById(id);
        return document.getContent();
    }

    @Inject
    GitRepositoryService gitRepositoryService;

    /**
     * Ingests a Git repository by cloning it and processing all code/text files.
     * Automatically detects and includes code files from 50+ programming languages.
     *
     * @param projectId The project ID to associate documents with
     * @param repoUrl   The Git repository URL (required)
     * @param branch    The branch to clone (optional, defaults to main/master)
     * @return Response with ingestion statistics
     */
    @POST
    @Path("/projects/{projectId}/ingest-repo")
    @Produces(MediaType.APPLICATION_JSON)
    public Response ingestRepository(
            @PathParam("projectId") UUID projectId,
            @QueryParam("repoUrl") String repoUrl,
            @QueryParam("branch") String branch) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required");
        }

        // Verify project exists
        if (projectService.findById(projectId) == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Project not found"))
                    .build();
        }

        // Clone and extract all code/text files (no pattern filtering)
        var files = gitRepositoryService.cloneAndExtractFiles(repoUrl, branch, null);

        // Get project entity
        var project = projectService.findById(projectId);

        // Process each file as a document
        int processedCount = 0;
        for (var file : files) {
            try {
                // Detect document type based on file extension
                DocumentType docType = detectDocumentType(file.fileName());

                // Create document entity
                Document document = new Document(
                        docType,
                        file.relativePath(), // Use relative path as filename for better identification
                        file.content(),
                        null, // no additional metadata
                        project);

                // Persist document
                documentService.create(document);
                processedCount++;

            } catch (Exception e) {
                // Log error but continue processing other files
                System.err.println("Failed to process file: " + file.relativePath() + " - " + e.getMessage());
            }
        }

        return Response.ok(new GitIngestResponse(
                files.size(),
                processedCount,
                "success",
                projectId)).build();
    }

    /**
     * Detects document type based on file extension.
     * Uses centralized CodeFileExtensions for 50+ programming languages.
     */
    private DocumentType detectDocumentType(String fileName) {
        if (fileName == null) {
            return DocumentType.TEXT;
        }

        return CodeFileExtensions.isCodeFile(fileName) ? DocumentType.CODE : DocumentType.TEXT;
    }

    /**
     * Deletes a document and all associated data (vectors and graph
     * entities/relations).
     * 
     * By default, shared entities (referenced by multiple documents) are
     * intelligently
     * rebuilt using cached extraction data. Use skipRebuild=true to delete all
     * affected
     * entities without rebuilding.
     * 
     * @param id          The document ID to delete
     * @param projectId   The project ID (required for graph cleanup)
     * @param skipRebuild If true, skips KG rebuild and deletes all affected
     *                    entities (default: false)
     * @return Response with no content (204) on success
     */
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(
            @PathParam("id") final UUID id,
            @QueryParam("projectId") final UUID projectId,
            @QueryParam("skipRebuild") final Boolean skipRebuild) {
        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required");
        }

        // T036: Check authorization before deleting document
        authService.checkWriteAccess(projectId);

        final boolean skip = skipRebuild != null && skipRebuild;
        documentService.delete(id, projectId, skip);
        return Response.noContent().build();
    }
}
