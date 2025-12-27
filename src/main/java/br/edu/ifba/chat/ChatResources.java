package br.edu.ifba.chat;

import br.edu.ifba.security.ProjectAuthorizationService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/chat")
@RolesAllowed({ "user", "admin" })
public class ChatResources {

    @Inject
    ChatService chatService;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ProjectAuthorizationService authService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatResponse chat(@Valid final ChatRequest request) {
        // T038: Check authorization before chatting with project
        authService.checkReadAccess(request.projectId());
        return chatService.chat(request);
    }
}
