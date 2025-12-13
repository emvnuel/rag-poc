package br.edu.ifba.project;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import br.edu.ifba.document.DocumentInfoResponse;
import br.edu.ifba.document.DocumentServicePort;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/projects")
public class ProjectResources {

    @Inject
    ProjectServicePort projectService;

    @Inject
    DocumentServicePort documentService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@Valid final ProjectCreateRequest request) {
        final Project project = new Project(request.name());
        final Project created = projectService.create(project);

        return Response.created(URI.create("/projects/" + created.getId()))
                .entity(new ProjectCreatedResponse(created.getId()))
                .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ProjectInfoResponse getById(@PathParam("id") final UUID id) {
        final Project project = projectService.findById(id);
        return new ProjectInfoResponse(
                project.getId(),
                project.getName(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                project.getDocuments().size()
        );
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProjectInfoResponse> getAll() {
        return projectService.findAll().stream()
                .map(project -> new ProjectInfoResponse(
                        project.getId(),
                        project.getName(),
                        project.getCreatedAt(),
                        project.getUpdatedAt(),
                        project.getDocuments().size()
                ))
                .collect(Collectors.toList());
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ProjectInfoResponse update(@PathParam("id") final UUID id, @Valid final ProjectUpdateRequest request) {
        final Project project = projectService.update(id, request.name());
        return new ProjectInfoResponse(
                project.getId(),
                project.getName(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                project.getDocuments().size()
        );
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") final UUID id) {
        projectService.delete(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/documents")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DocumentInfoResponse> getDocuments(@PathParam("id") final UUID id) {
        projectService.findById(id);
        return documentService.findByProjectId(id).stream()
                .map(document -> new DocumentInfoResponse(
                        document.getId(),
                        document.getType(),
                        document.getStatus(),
                        document.getFileName(),
                        document.getMetadata(),
                        document.getCreatedAt(),
                        document.getUpdatedAt()
                ))
                .collect(Collectors.toList());
    }
}
