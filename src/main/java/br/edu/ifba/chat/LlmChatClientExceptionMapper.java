package br.edu.ifba.chat;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.jboss.logging.Logger;

/**
 * Exception mapper for LLM Chat Client to capture and log detailed error responses.
 * This helps debug issues like 400 Bad Request by including the response body.
 */
public class LlmChatClientExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

    private static final Logger LOG = Logger.getLogger(LlmChatClientExceptionMapper.class);

    @Override
    public RuntimeException toThrowable(Response response) {
        // Only handle error responses (4xx and 5xx)
        if (response.getStatus() < 400) {
            return null;
        }

        String responseBody = null;
        try {
            // Try to read the response body as text
            if (response.hasEntity()) {
                responseBody = response.readEntity(String.class);
            }
        } catch (Exception e) {
            LOG.warn("Failed to read error response body", e);
        }

        int status = response.getStatus();
        String statusInfo = response.getStatusInfo().getReasonPhrase();

        // Log the detailed error with full response
        LOG.errorf("========== LLM API ERROR ==========");
        LOG.errorf("Status: %d %s", status, statusInfo);
        LOG.errorf("Response Headers: %s", response.getHeaders());
        if (responseBody != null && !responseBody.isEmpty()) {
            LOG.errorf("Response Body: %s", responseBody);
        } else {
            LOG.errorf("Response Body: (empty)");
        }
        LOG.errorf("===================================");

        // Create detailed exception message
        String errorMessage = String.format(
            "LLM API returned %d %s%s",
            status,
            statusInfo,
            responseBody != null ? " - " + responseBody : ""
        );

        return new WebApplicationException(errorMessage, response);
    }

    @Override
    public int getPriority() {
        return 4000;
    }
}
