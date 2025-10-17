package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PdfDocumentExtractor implements DocumentExtractor {

    @Override
    public String extract(final InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return fileName.endsWith(".pdf");
    }
}
