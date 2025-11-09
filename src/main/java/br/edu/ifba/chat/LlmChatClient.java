package br.edu.ifba.chat;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "llm-chat")
@RegisterProvider(LlmChatClientExceptionMapper.class)
@ClientHeaderParam(name = "Authorization", value = "{lookupAuth}")
public interface LlmChatClient {

    @POST
    @Path("/chat/completions")
    LlmChatResponse chat(LlmChatRequest request);

    default String lookupAuth() {
        return ConfigProvider.getConfig()
            .getOptionalValue("llm-chat.api-key", String.class)
            .map(key -> "Bearer " + key)
            .orElse(null);
    }
}
