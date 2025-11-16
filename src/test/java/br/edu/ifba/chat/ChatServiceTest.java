package br.edu.ifba.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import br.edu.ifba.document.SearchResult;
import br.edu.ifba.document.SearchService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ChatServiceTest {

    @Inject
    ChatService chatService;

    @InjectMock
    SearchService searchService;

    @InjectMock
    @RestClient
    LlmChatClient chatClient;

    @Test
    void testChatBasicRequest() {
        final UUID projectId = UUID.randomUUID();
        final String userMessage = "What is RAG?";
        
        final ChatRequest request = new ChatRequest(projectId, userMessage, null);
        
        assertNotNull(request.projectId());
        assertEquals(userMessage, request.message());
    }

    @Test
    void testChatRequestWithHistory() {
        final UUID projectId = UUID.randomUUID();
        final String userMessage = "Tell me more";
        
        final List<ChatMessage> history = List.of(
            new ChatMessage("user", "What is RAG?"),
            new ChatMessage("assistant", "RAG stands for Retrieval-Augmented Generation.")
        );
        
        final ChatRequest request = new ChatRequest(projectId, userMessage, history);
        
        assertNotNull(request.projectId());
        assertEquals(userMessage, request.message());
        assertEquals(2, request.history().size());
    }

    @Test
    void testChatResponseStructure() {
        final String responseText = "This is the answer";
        final List<ChatMessage> messages = List.of(
            new ChatMessage("user", "Question"),
            new ChatMessage("assistant", responseText)
        );
        final List<SearchResult> sources = List.of(
            new SearchResult("chunk_123", UUID.randomUUID(), "chunk text", 0, "file.txt", 0.85)
        );
        
        final ChatResponse response = new ChatResponse(
            responseText, 
            messages, 
            sources, 
            "llama3.2",
            100000L,
            10L,
            50L
        );
        
        assertNotNull(response);
        assertEquals(responseText, response.response());
        assertEquals(2, response.messages().size());
        assertEquals(1, response.sources().size());
        assertEquals("llama3.2", response.model());
    }
}
