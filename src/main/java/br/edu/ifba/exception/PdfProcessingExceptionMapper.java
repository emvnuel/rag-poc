package br.edu.ifba.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PdfProcessingExceptionMapper implements ExceptionMapper<PdfProcessingException> {
    
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(final PdfProcessingException exception) {
        final ErrorResponse error = new ErrorResponse(
            "about:blank",
            "Internal Server Error",
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
            exception.getMessage(),
            uriInfo.getPath()
        );
        
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(error)
                .type("application/problem+json")
                .build();
    }
}
