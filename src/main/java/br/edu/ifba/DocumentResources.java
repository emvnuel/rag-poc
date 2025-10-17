package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import jakarta.validation.Valid;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import br.edu.ifba.exception.FileUploadException;
import br.edu.ifba.exception.PdfProcessingException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/documents")
public class DocumentResources {

    @Inject
    DocumentExtractorFactory extractorFactory;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/files")
    public DocumentResponse upload(@FormParam("file") FileUpload file) {
        if (file == null) {
            throw new FileUploadException("No file uploaded");
        }

        final String fileName = file.fileName().toLowerCase();

        try (InputStream inputStream = file.uploadedFile().toFile().toPath().toUri().toURL().openStream()) {
            final DocumentExtractor extractor = extractorFactory.getExtractor(fileName);
            final String text = extractor.extract(inputStream);
            final String formattedText = TextFormatter.format(text);
            final List<String> chunks = TextChunker.chunkText(formattedText, 500);

            return new DocumentResponse(chunks);
        } catch (IOException e) {
            throw new PdfProcessingException("Error processing document: " + e.getMessage(), e);
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/texts")
    public DocumentResponse processText(@Valid final TextRequest request) {

        final String formattedText = TextFormatter.format(request.text());
        final List<String> chunks = TextChunker.chunkText(formattedText, 500);
        
        return new DocumentResponse(chunks);
    }
}
