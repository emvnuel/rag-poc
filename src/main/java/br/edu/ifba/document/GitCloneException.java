package br.edu.ifba.document;

/**
 * Exception thrown when Git repository cloning or processing fails.
 */
public class GitCloneException extends RuntimeException {

    public GitCloneException(String message) {
        super(message);
    }

    public GitCloneException(String message, Throwable cause) {
        super(message, cause);
    }
}
