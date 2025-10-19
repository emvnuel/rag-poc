package br.edu.ifba.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(final IllegalArgumentException exception) {
        final ErrorResponse error = new ErrorResponse(
            "about:blank",
            "Not Found",
            Response.Status.NOT_FOUND.getStatusCode(),
            exception.getMessage(),
            uriInfo.getPath()
        );

        return Response.status(Response.Status.NOT_FOUND)
                .entity(error)
                .type("application/problem+json")
                .build();
    }
}
