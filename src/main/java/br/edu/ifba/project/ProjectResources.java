package br.edu.ifba.project;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import br.edu.ifba.document.DocumentInfoResponse;
import br.edu.ifba.document.DocumentRepositoryPort;
import br.edu.ifba.document.DocumentServicePort;
import br.edu.ifba.security.ProjectAuthorizationService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
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
@RolesAllowed({ "user", "admin" })
public class ProjectResources {

    @Inject
    ProjectServicePort projectService;

    @Inject
    DocumentServicePort documentService;

    @Inject
    DocumentRepositoryPort documentRepository;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ProjectAuthorizationService authService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@Valid final ProjectCreateRequest request) {
        // T028: Set ownerId to current user
        final String currentUserId = authService.getCurrentUserId();
        final Project project = new Project(request.name(), currentUserId);
        final Project created = projectService.create(project);

        return Response.created(URI.create("/projects/" + created.getId()))
                .entity(new ProjectCreatedResponse(created.getId()))
                .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ProjectInfoResponse getById(@PathParam("id") final UUID id) {
        // T030: Add authorization check
        authService.checkReadAccess(id);
        final Project project = projectService.findById(id);
        return new ProjectInfoResponse(
                project.getId(),
                project.getName(),
                project.getOwnerId(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                documentRepository.countByProjectId(project.getId()));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProjectInfoResponse> getAll() {
        // T033: Filter projects by ownership (users see own, admins see all)
        List<Project> projects;
        if (authService.isAdmin()) {
            projects = projectService.findAll();
        } else {
            // For users, filter to show only their own projects and legacy projects
            final String currentUserId = authService.getCurrentUserId();
            projects = projectService.findAll().stream()
                    .filter(p -> p.getOwnerId() == null || p.getOwnerId().equals(currentUserId))
                    .collect(Collectors.toList());
        }

        return projects.stream()
                .map(project -> new ProjectInfoResponse(
                        project.getId(),
                        project.getName(),
                        project.getOwnerId(),
                        project.getCreatedAt(),
                        project.getUpdatedAt(),
                        documentRepository.countByProjectId(project.getId())))
                .collect(Collectors.toList());
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ProjectInfoResponse update(@PathParam("id") final UUID id, @Valid final ProjectUpdateRequest request) {
        // T031: Add authorization check
        authService.checkWriteAccess(id);
        final Project project = projectService.update(id, request.name());
        return new ProjectInfoResponse(
                project.getId(),
                project.getName(),
                project.getOwnerId(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                documentRepository.countByProjectId(project.getId()));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") final UUID id) {
        // T032: Add authorization check
        authService.checkWriteAccess(id);
        projectService.delete(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/documents")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DocumentInfoResponse> getDocuments(@PathParam("id") final UUID id) {
        // Check read access before listing documents
        authService.checkReadAccess(id);
        projectService.findById(id);
        return documentService.findByProjectId(id).stream()
                .map(document -> new DocumentInfoResponse(
                        document.getId(),
                        document.getType(),
                        document.getStatus(),
                        document.getFileName(),
                        document.getMetadata(),
                        document.getCreatedAt(),
                        document.getUpdatedAt()))
                .collect(Collectors.toList());
    }
}
