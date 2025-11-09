package br.edu.ifba.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import br.edu.ifba.exception.FileUploadException;
import br.edu.ifba.exception.PdfProcessingException;
import br.edu.ifba.project.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/documents")
public class DocumentResources {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    DocumentService documentService;

    @Inject
    ProjectService projectService;

    @Inject
    DocumentExtractorFactory extractorFactory;

    @Inject
    SearchService searchService;

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

        final String fileName = file.fileName().toLowerCase();

        try (InputStream textStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream();
             InputStream metadataStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream()) {
            
            final DocumentExtractor extractor = extractorFactory.getExtractor(fileName);
            final String text = extractor.extract(textStream);
            final Map<String, Object> contentMetadata = extractor.extractMetadata(metadataStream);
            final String formattedText = TextFormatter.format(text);
            final String metadata = FileMetadataExtractor.extractMetadata(file, contentMetadata);

            final var project = projectService.findById(projectId);
            final Document document = new Document(DocumentType.FILE, fileName, formattedText, metadata, project);
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
        final Document document = new Document(DocumentType.TEXT, "text-input", formattedText, null, project);
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
            final Document document = new Document(DocumentType.WEBSITE, request.url(), formattedText, metadata, project);
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
                document.getUpdatedAt()
        );
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
}
