package br.edu.ifba.exception;

public class PdfProcessingException extends RuntimeException {
    
    public PdfProcessingException(final String message) {
        super(message);
    }
    
    public PdfProcessingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
