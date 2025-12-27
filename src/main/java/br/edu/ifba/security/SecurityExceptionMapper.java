package br.edu.ifba.security;

import br.edu.ifba.exception.ForbiddenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.time.Instant;
import java.util.Map;

/**
 * Maps ForbiddenException to HTTP 403 Forbidden responses.
 * Provides consistent error response format for authorization failures.
 */
@Provider
public class SecurityExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Override
    public Response toResponse(ForbiddenException exception) {
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error", "Forbidden",
                        "message", exception.getMessage(),
                        "timestamp", Instant.now().toString()))
                .build();
    }
}
