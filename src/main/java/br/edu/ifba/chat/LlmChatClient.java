package br.edu.ifba.chat;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "llm-chat")
public interface LlmChatClient {

    @POST
    @Path("/v1/chat/completions")
    LlmChatResponse chat(LlmChatRequest request);
}
