package br.edu.ifba.exception;

/**
 * Exception thrown when a user lacks authorization to access a resource.
 * This exception is mapped to HTTP 403 Forbidden response by
 * SecurityExceptionMapper.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
