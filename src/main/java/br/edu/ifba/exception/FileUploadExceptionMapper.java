package br.edu.ifba.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class FileUploadExceptionMapper implements ExceptionMapper<FileUploadException> {
    
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(final FileUploadException exception) {
        final ErrorResponse error = new ErrorResponse(
            "about:blank",
            "Bad Request",
            Response.Status.BAD_REQUEST.getStatusCode(),
            exception.getMessage(),
            uriInfo.getPath()
        );
        
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(error)
                .type("application/problem+json")
                .build();
    }
}
