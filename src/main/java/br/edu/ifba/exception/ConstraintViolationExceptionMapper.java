package br.edu.ifba.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(final ConstraintViolationException exception) {
        final String detail = exception.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .findFirst()
                .orElse("Validation failed");

        final ErrorResponse error = new ErrorResponse(
            "about:blank",
            "Bad Request",
            Response.Status.BAD_REQUEST.getStatusCode(),
            detail,
            uriInfo.getPath()
        );

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(error)
                .type("application/problem+json")
                .build();
    }
}
