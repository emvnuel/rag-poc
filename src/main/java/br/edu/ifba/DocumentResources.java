package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import br.edu.ifba.exception.FileUploadException;
import br.edu.ifba.exception.PdfProcessingException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

@Path("/documents")
public class DocumentResources {

    @Inject
    DocumentExtractorFactory extractorFactory;

    @Inject
    DocumentService documentService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/files")
    public Response upload(@FormParam("file") FileUpload file) {
        if (file == null) {
            throw new FileUploadException("No file uploaded");
        }

        final String fileName = file.fileName().toLowerCase();

        try (InputStream textStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream();
             InputStream metadataStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream()) {
            
            final DocumentExtractor extractor = extractorFactory.getExtractor(fileName);
            final String text = extractor.extract(textStream);
            final Map<String, Object> contentMetadata = extractor.extractMetadata(metadataStream);
            final String formattedText = TextFormatter.format(text);
            final String metadata = FileMetadataExtractor.extractMetadata(file, contentMetadata);

            final Document document = new Document(DocumentType.FILE, fileName, formattedText, metadata);
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

        final Document document = new Document(DocumentType.TEXT, "text-input", formattedText, null);
        final Document created = documentService.create(document);

        return Response.created(URI.create("/documents/" + created.getId()))
                .entity(new DocumentCreatedResponse(created.getId()))
                .build();
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
}
