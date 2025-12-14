package br.edu.ifba.exception;

import br.edu.ifba.document.GitCloneException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps GitCloneException to HTTP 502 Bad Gateway responses.
 */
@Provider
public class GitCloneExceptionMapper implements ExceptionMapper<GitCloneException> {

    @Override
    public Response toResponse(GitCloneException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(new ErrorResponse(
                        "about:blank",
                        "Git Clone Failed",
                        Response.Status.BAD_GATEWAY.getStatusCode(),
                        exception.getMessage(),
                        null
                ))
                .build();
    }
}
