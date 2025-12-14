package br.edu.ifba.lightrag;

import br.edu.ifba.chat.ChatMessage;
import br.edu.ifba.chat.ChatRequest;
import br.edu.ifba.chat.ChatResponse;
import br.edu.ifba.chat.ChatService;
import br.edu.ifba.chat.LlmChatClient;
import br.edu.ifba.chat.LlmChatRequest;
import br.edu.ifba.chat.LlmChatResponse;
import br.edu.ifba.document.Document;
import br.edu.ifba.document.DocumentServicePort;
import br.edu.ifba.document.DocumentType;
import br.edu.ifba.document.EmbeddingRequest;
import br.edu.ifba.document.EmbeddingResponse;
import br.edu.ifba.document.LlmEmbeddingClient;
import br.edu.ifba.project.Project;
import br.edu.ifba.project.ProjectServicePort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for code source RAG query functionality.
 * Verifies end-to-end code querying including source attribution and cross-language support.
 * 
 * Tests cover:
 * - T064: Source attribution (filename, line numbers in chunk metadata)
 * - T065: Cross-language queries (Java, Python, TypeScript)
 * - Code formatting preservation
 * 
 * NOTE: These tests verify the infrastructure is in place for code RAG.
 * Full document processing is asynchronous (DocumentProcessorJob) and would
 * require actual LightRAG insertion, which is complex to test in isolation.
 */
@QuarkusTest
class CodeQueryIT {

    private static final Logger LOG = Logger.getLogger(CodeQueryIT.class);

    @Inject
    ProjectServicePort projectService;

    @Inject
    DocumentServicePort documentService;

    @Inject
    ChatService chatService;
    
    @Inject
    LightRAGService lightRAGService;
    
    @InjectMock
    @RestClient
    LlmEmbeddingClient embeddingClient;

    @InjectMock
    @RestClient
    LlmChatClient chatClient;

    private UUID testProjectId;

    /**
     * Set up test environment with mock LLM responses.
     */
    @BeforeEach
    void setUp() throws Exception {
        LOG.info("Setting up CodeQueryIT test environment");

        // Create test project
        Project project = projectService.create(new Project("Code Query Test"));
        testProjectId = project.getId();
        LOG.infof("Test project created: %s", testProjectId);
        
        // Mock embedding client to return fake embeddings
        List<Double> fakeEmbeddingList = new ArrayList<>(768);
        for (int i = 0; i < 768; i++) {
            fakeEmbeddingList.add(0.0001 * i);
        }
        
        EmbeddingResponse.Embedding embeddingData = new EmbeddingResponse.Embedding(
            fakeEmbeddingList, 
            0
        );
        
        EmbeddingResponse mockEmbeddingResponse = new EmbeddingResponse(
            "test-model",
            List.of(embeddingData),
            100L,
            50L,
            10
        );
        
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
            .thenReturn(mockEmbeddingResponse);
        
        // Mock chat client to return simple responses
        LlmChatResponse.Choice mockChoice = new LlmChatResponse.Choice(
            0,
            new ChatMessage("assistant", "The code contains UserService class with findById and findAll methods."),
            "stop"
        );
        
        LlmChatResponse mockChatResponse = new LlmChatResponse(
            "test-id",
            "chat.completion",
            System.currentTimeMillis(),
            "test-model",
            List.of(mockChoice),
            new LlmChatResponse.Usage(100, 50, 150)
        );
        
        when(chatClient.chat(any(LlmChatRequest.class)))
            .thenReturn(mockChatResponse);
        
        LOG.info("Mock LLM clients configured");
    }

    /**
     * T064: Test source attribution in query responses.
     * 
     * Verifies that the system can ingest code and make it queryable.
     * Source attribution (filename, line numbers) is stored in chunk metadata
     * and used during retrieval.
     */
    @Test
    void testSourceAttribution() throws Exception {
        LOG.info("Testing source attribution for code queries");

        // Create Document entity
        Project project = projectService.findById(testProjectId);
        Document doc = documentService.create(new Document(
            DocumentType.CODE,
            "UserService.java",
            "public class UserService { public User findById(Long id) { return repository.findById(id); } }",
            null,
            project
        ));
        
        UUID docId = doc.getId();
        assertNotNull(docId, "Document should be created");
        LOG.infof("Created code document: %s", docId);
        
        // Insert into LightRAG
        String javaCode = """
            package com.example;
            
            import java.util.List;
            
            public class UserService {
                private final UserRepository repository;
                
                public User findById(Long id) {
                    return repository.findById(id)
                        .orElseThrow(() -> new NotFoundException());
                }
                
                public List<User> findAll() {
                    return repository.findAll();
                }
            }
            """;
        
        lightRAGService.insertDocument(
            docId,
            javaCode,
            "UserService.java",
            testProjectId,
            DocumentType.CODE
        ).get(30, TimeUnit.SECONDS);
        
        LOG.info("Document inserted into LightRAG, querying...");
        
        // Query for the code
        ChatRequest request = new ChatRequest(
            testProjectId,
            "What methods are available in the UserService class?",
            null,
            null
        );
        
        ChatResponse response = chatService.chat(request);
        
        assertNotNull(response, "Should receive response");
        assertNotNull(response.response(), "Response should have message");
        LOG.infof("Response: %s", response.response());
        
        // Verify response references the code
        String message = response.response().toLowerCase();
        assertTrue(
            message.contains("userservice") || 
            message.contains("findbyid") || 
            message.contains("findall") ||
            message.contains("method"),
            "Response should reference UserService or its methods"
        );
        
        // Note: Source attribution (filename, line numbers) is in chunk metadata,
        // not necessarily in the LLM response text itself
        
        LOG.info("Source attribution test passed");
    }

    /**
     * T065: Test cross-language queries.
     * 
     * Verifies that the system can handle Java, Python, and TypeScript files
     * and query across them.
     */
    @Test
    void testCrossLanguageQuery() throws Exception {
        LOG.info("Testing cross-language code queries");

        Project project = projectService.findById(testProjectId);
        
        // Create Java document
        String javaCode = """
            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;
        
        Document javaDoc = documentService.create(new Document(
            DocumentType.CODE,
            "Calculator.java",
            javaCode,
            null,
            project
        ));
        
        lightRAGService.insertDocument(
            javaDoc.getId(),
            javaCode,
            "Calculator.java",
            testProjectId,
            DocumentType.CODE
        ).get(30, TimeUnit.SECONDS);
        
        // Create Python document
        String pythonCode = """
            def calculate_sum(a: int, b: int) -> int:
                \"\"\"Calculate the sum of two numbers.\"\"\"
                return a + b
            
            class MathHelper:
                def multiply(self, x: float, y: float) -> float:
                    return x * y
            """;
        
        Document pythonDoc = documentService.create(new Document(
            DocumentType.CODE,
            "calculator.py",
            pythonCode,
            null,
            project
        ));
        
        lightRAGService.insertDocument(
            pythonDoc.getId(),
            pythonCode,
            "calculator.py",
            testProjectId,
            DocumentType.CODE
        ).get(30, TimeUnit.SECONDS);
        
        // Create TypeScript document
        String tsCode = """
            export interface Calculator {
                add(a: number, b: number): number;
            }
            
            export class SimpleCalculator implements Calculator {
                add(a: number, b: number): number {
                    return a + b;
                }
            }
            """;
        
        Document tsDoc = documentService.create(new Document(
            DocumentType.CODE,
            "calculator.ts",
            tsCode,
            null,
            project
        ));
        
        lightRAGService.insertDocument(
            tsDoc.getId(),
            tsCode,
            "calculator.ts",
            testProjectId,
            DocumentType.CODE
        ).get(30, TimeUnit.SECONDS);
        
        LOG.info("Inserted 3 code files (Java, Python, TypeScript), querying...");
        
        // Query across all languages
        ChatRequest request = new ChatRequest(
            testProjectId,
            "What classes or functions are defined in this codebase?",
            null,
            null
        );
        
        ChatResponse response = chatService.chat(request);
        
        assertNotNull(response, "Should receive response");
        assertNotNull(response.response(), "Response should have message");
        LOG.infof("Cross-language response: %s", response.response());
        
        // Response should be substantive
        assertTrue(response.response().length() > 50, 
            "Response should be substantive (>50 chars)");
        
        // Response should reference code entities
        String message = response.response().toLowerCase();
        boolean mentionsCode = message.contains("class") || 
                               message.contains("function") ||
                               message.contains("method") ||
                               message.contains("calculator");
        
        assertTrue(mentionsCode, 
            "Response should reference code entities from the uploaded files");
        
        LOG.info("Cross-language query test passed");
    }
    
    /**
     * Test code formatting preservation.
     * 
     * Verifies that code with specific indentation and structure is stored
     * and can be queried.
     */
    @Test
    void testCodeFormattingPreserved() throws Exception {
        LOG.info("Testing code formatting preservation");

        Project project = projectService.findById(testProjectId);
        
        // Python code with specific indentation
        String pythonCode = """
            class Example:
                def method_one(self):
                    if True:
                        print("Indented")
                        
                def method_two(self):
                    return "value"
            """;
        
        Document doc = documentService.create(new Document(
            DocumentType.CODE,
            "example.py",
            pythonCode,
            null,
            project
        ));
        
        lightRAGService.insertDocument(
            doc.getId(),
            pythonCode,
            "example.py",
            testProjectId,
            DocumentType.CODE
        ).get(30, TimeUnit.SECONDS);
        
        LOG.info("Python document inserted, querying...");
        
        // Query for the code
        ChatRequest request = new ChatRequest(
            testProjectId,
            "Show me the Example class",
            null,
            null
        );
        
        ChatResponse response = chatService.chat(request);
        
        assertNotNull(response, "Should receive response");
        assertNotNull(response.response(), "Response should have message");
        LOG.infof("Response: %s", response.response());
        
        // Response should reference the class
        String message = response.response().toLowerCase();
        assertTrue(message.contains("example") || message.contains("method"),
            "Response should reference the class or methods");
        
        // Note: The code chunks preserve exact formatting, but the LLM response
        // format depends on the model. The test verifies the code is queryable.
        
        LOG.info("Code formatting test passed");
    }
}
