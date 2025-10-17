package br.edu.ifba.exception;

public class FileUploadException extends RuntimeException {
    
    public FileUploadException(final String message) {
        super(message);
    }
    
    public FileUploadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
