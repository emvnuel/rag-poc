package br.edu.ifba.security;

import java.util.UUID;

import br.edu.ifba.exception.ForbiddenException;
import br.edu.ifba.project.Project;
import br.edu.ifba.project.ProjectRepositoryPort;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Centralized project authorization service that validates user access to
 * projects.
 * Implements ownership-based access control on top of role-based
 * authentication.
 * 
 * Authorization rules:
 * - Admins have full access to all projects
 * - Users can read/write their own projects (where ownerId matches their
 * subject ID)
 * - Legacy projects (ownerId = null) are readable by all, but only modifiable
 * by admins
 */
@ApplicationScoped
public class ProjectAuthorizationService {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ProjectRepositoryPort projectRepository;

    /**
     * Checks if the current user has read access to the specified project.
     * 
     * @param projectId the project ID to check
     * @throws ForbiddenException if the user lacks read access
     */
    public void checkReadAccess(UUID projectId) {
        Project project = projectRepository.findByIdOrThrow(projectId);

        // Admins can read any project
        if (isAdmin()) {
            return;
        }

        // Legacy projects (no owner) are readable by all authenticated users
        if (project.getOwnerId() == null) {
            return;
        }

        // Users can read their own projects
        if (project.getOwnerId().equals(getCurrentUserId())) {
            return;
        }

        throw new ForbiddenException("Access denied to project: " + projectId);
    }

    /**
     * Checks if the current user has write access to the specified project.
     * 
     * @param projectId the project ID to check
     * @throws ForbiddenException if the user lacks write access
     */
    public void checkWriteAccess(UUID projectId) {
        Project project = projectRepository.findByIdOrThrow(projectId);

        // Admins can modify any project
        if (isAdmin()) {
            return;
        }

        // Legacy projects (no owner) can only be modified by admins
        if (project.getOwnerId() == null) {
            throw new ForbiddenException("Legacy projects can only be modified by administrators");
        }

        // Users can modify their own projects
        if (project.getOwnerId().equals(getCurrentUserId())) {
            return;
        }

        throw new ForbiddenException("Access denied to project: " + projectId);
    }

    /**
     * Gets the current authenticated user's ID (Keycloak subject ID).
     * 
     * @return the user's subject ID from the JWT token
     */
    public String getCurrentUserId() {
        return securityIdentity.getPrincipal().getName();
    }

    /**
     * Checks if the current user has the admin role.
     * 
     * @return true if user has admin role, false otherwise
     */
    public boolean isAdmin() {
        return securityIdentity.hasRole("admin");
    }
}
