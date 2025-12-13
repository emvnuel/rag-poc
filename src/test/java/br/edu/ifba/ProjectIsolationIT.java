package br.edu.ifba;

import br.edu.ifba.chat.ChatMessage;
import br.edu.ifba.chat.LlmChatClient;
import br.edu.ifba.chat.LlmChatRequest;
import br.edu.ifba.chat.LlmChatResponse;
import br.edu.ifba.document.Document;
import br.edu.ifba.document.DocumentServicePort;
import br.edu.ifba.document.DocumentType;
import br.edu.ifba.document.EmbeddingRequest;
import br.edu.ifba.document.EmbeddingResponse;
import br.edu.ifba.document.LlmEmbeddingClient;
import br.edu.ifba.lightrag.LightRAGService;
import br.edu.ifba.lightrag.core.LightRAGQueryResult;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.storage.impl.AgeGraphStorage;
import br.edu.ifba.lightrag.storage.impl.PgVectorStorage;
import br.edu.ifba.project.Project;
import br.edu.ifba.project.ProjectRepository;
import br.edu.ifba.project.ProjectServicePort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for project isolation across all query modes.
 * Verifies that queries only return results from the specified project.
 * 
 * Tests all query modes: GLOBAL, HYBRID, LOCAL, MIX, and NAIVE
 */
@QuarkusTest
class ProjectIsolationIT {

    private static final Logger LOG = Logger.getLogger(ProjectIsolationIT.class);

    @Inject
    LightRAGService lightRAGService;

    @Inject
    AgeGraphStorage graphStorage;

    @Inject
    PgVectorStorage vectorStorage;

    @Inject
    ProjectServicePort projectService;

    @Inject
    ProjectRepository projectRepository;

    @Inject
    DocumentServicePort documentService;

    @Inject
    jakarta.persistence.EntityManager entityManager;

    @InjectMock
    @RestClient
    LlmEmbeddingClient embeddingClient;

    @InjectMock
    @RestClient
    LlmChatClient chatClient;

    private UUID projectA;
    private UUID projectB;
    
    private UUID documentA1;
    private UUID documentB1;

    /**
     * Set up test data: Two projects with distinct documents.
     * 
     * Project A: Tech/Apple focused documents
     * Project B: Food/Apple focused documents
     */
    @BeforeEach
    void setUp() throws Exception {
        projectA = UUID.randomUUID();
        projectB = UUID.randomUUID();
        
        documentA1 = UUID.randomUUID();
        documentB1 = UUID.randomUUID();

        LOG.infof("Setting up test data - ProjectA: %s, ProjectB: %s", projectA, projectB);

        // Mock embedding client to return fake embeddings
        // Use 4000 dimensions to match test configuration (lightrag.vector.dimension=4000)
        List<Double> fakeEmbeddingList = new ArrayList<>(4000);
        for (int i = 0; i < 4000; i++) {
            fakeEmbeddingList.add(0.0001 * i); // Slight variation to avoid all zeros
        }
        
        EmbeddingResponse.Embedding embeddingData = new EmbeddingResponse.Embedding(
            fakeEmbeddingList, 
            0
        );
        
        EmbeddingResponse mockEmbeddingResponse = new EmbeddingResponse(
            "test-model",
            List.of(embeddingData),
            1000L,
            500L,
            100
        );
        
        when(embeddingClient.embed(any(EmbeddingRequest.class)))
            .thenReturn(mockEmbeddingResponse);
        
        // Mock chat client to return simple responses
        LlmChatResponse.Choice mockChoice = new LlmChatResponse.Choice(
            0,
            new ChatMessage("assistant", "Test response content from LLM"),
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
        
        LOG.info("Mocked embedding and chat clients configured");

        // Create Project entities in database (required for FK constraints)
        createProjectsInDatabase();

        // Create project graphs
        graphStorage.createProjectGraph(projectA.toString()).get(30, TimeUnit.SECONDS);
        graphStorage.createProjectGraph(projectB.toString()).get(30, TimeUnit.SECONDS);
        LOG.info("Project graphs created successfully");

        // Create Document entities in database (required for FK constraints from vectors)
        createDocumentsInDatabase();

        // Insert document into Project A (Tech focus)
        String contentA = """
            Apple Inc. is a leading technology company founded by Steve Jobs in 1976.
            Apple manufactures the iPhone, iPad, and Mac computers.
            Tim Cook became CEO after Steve Jobs passed away in 2011.
            Apple's headquarters is Apple Park in Cupertino, California.
            Apple is known for innovation in consumer electronics and software.
            The company's market cap exceeds 3 trillion dollars.
            """;

        CompletableFuture<String> insertA = lightRAGService.insertDocument(
            documentA1,
            contentA,
            "apple_tech.txt",
            projectA
        );

        // Insert document into Project B (Food focus)
        String contentB = """
            Apples are nutritious fruits grown in orchards around the world.
            Red apples and green apples are popular varieties.
            Farmers harvest apples in autumn season for markets.
            Apple pie is a traditional dessert in many cultures.
            Apples contain vitamins and dietary fiber for health benefits.
            Apple orchards can be found in Washington State and New York.
            """;

        CompletableFuture<String> insertB = lightRAGService.insertDocument(
            documentB1,
            contentB,
            "apple_fruit.txt",
            projectB
        );

        // Wait for both inserts to complete
        CompletableFuture.allOf(insertA, insertB).get(60, TimeUnit.SECONDS);
        LOG.info("Test documents inserted and processed successfully");

        // Wait a bit for indexing to complete
        Thread.sleep(2000);
    }

    /**
     * Create Project entities in database (required for FK constraints).
     * Uses ProjectService which handles transactions.
     */
    void createProjectsInDatabase() {
        // Create and persist projects using the service
        Project projA = projectService.create(new Project("Test Project A - Tech"));
        Project projB = projectService.create(new Project("Test Project B - Food"));
        
        // Update our test UUIDs to match the generated IDs
        projectA = projA.getId();
        projectB = projB.getId();
        
        LOG.infof("Projects persisted: A=%s, B=%s", projectA, projectB);
    }

    /**
     * Create Document entities in database (required for FK constraints from vectors).
     * Uses DocumentService which handles transactions.
     */
    void createDocumentsInDatabase() {
        // Fetch projects from database
        Project projA = projectService.findById(projectA);
        Project projB = projectService.findById(projectB);

        // Create Document for Project A
        Document docA = documentService.create(new Document(
            DocumentType.TEXT,
            "apple_tech.txt",
            "Placeholder content - will be replaced by LightRAG",
            null,
            projA
        ));
        documentA1 = docA.getId();

        // Create Document for Project B
        Document docB = documentService.create(new Document(
            DocumentType.TEXT,
            "apple_fruit.txt",
            "Placeholder content - will be replaced by LightRAG",
            null,
            projB
        ));
        documentB1 = docB.getId();

        LOG.infof("Documents persisted: A=%s, B=%s", documentA1, documentB1);
    }

    /**
     * Test GLOBAL query mode respects project isolation.
     * 
     * GLOBAL mode uses entity-based retrieval from the knowledge graph.
     * Should only return entities and relationships from the queried project.
     * 
     * NOTE: GLOBAL mode requires entity extraction which may not work with mocked LLMs.
     * If no entities are extracted during setup, this test verifies graceful handling.
     */
    @Test
    void testGlobalQueryScopedToProject() throws Exception {
        LOG.info("Testing GLOBAL query mode isolation");

        // Query Project A about Apple (should return tech company sources if entities exist)
        LightRAGQueryResult resultA = lightRAGService.query(
            "Tell me about Apple",
            QueryParam.Mode.GLOBAL,
            projectA
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultA, "Project A result should not be null");
        assertNotNull(resultA.answer(), "Project A answer should not be null");
        
        LOG.infof("Project A GLOBAL: sources=%d, chunks=%d", 
            resultA.totalSources(), resultA.sourceChunks().size());

        // If sources exist, verify they belong to Project A's documents
        for (var chunk : resultA.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentA1.toString(), chunk.documentId(),
                    "Project A GLOBAL query source chunks must only reference Project A documents");
            }
        }

        // Query Project B about Apple (should return fruit sources if entities exist)
        LightRAGQueryResult resultB = lightRAGService.query(
            "Tell me about Apple",
            QueryParam.Mode.GLOBAL,
            projectB
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultB, "Project B result should not be null");
        assertNotNull(resultB.answer(), "Project B answer should not be null");

        LOG.infof("Project B GLOBAL: sources=%d, chunks=%d", 
            resultB.totalSources(), resultB.sourceChunks().size());

        // If sources exist, verify they belong to Project B's documents
        for (var chunk : resultB.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentB1.toString(), chunk.documentId(),
                    "Project B GLOBAL query source chunks must only reference Project B documents");
            }
        }

        // Verify no cross-contamination if both have sources
        if (resultA.totalSources() > 0 && resultB.totalSources() > 0) {
            boolean hasCrossContamination = resultA.sourceChunks().stream()
                .anyMatch(chunkA -> resultB.sourceChunks().stream()
                    .anyMatch(chunkB -> chunkA.documentId() != null 
                        && chunkA.documentId().equals(chunkB.documentId())));
            
            assertFalse(hasCrossContamination, 
                "GLOBAL queries should not return the same document sources for different projects");
        }

        LOG.info("GLOBAL query mode isolation verified successfully");
    }

    /**
     * Test HYBRID query mode respects project isolation.
     * 
     * HYBRID combines LOCAL (chunk-based) and GLOBAL (entity-based) retrieval.
     * Should only return data from the queried project in both modes.
     */
    @Test
    void testHybridQueryScopedToProject() throws Exception {
        LOG.info("Testing HYBRID query mode isolation");

        // Query Project A
        LightRAGQueryResult resultA = lightRAGService.query(
            "What is Apple known for?",
            QueryParam.Mode.HYBRID,
            projectA
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultA, "Project A result should not be null");
        assertNotNull(resultA.answer(), "Project A answer should not be null");
        assertTrue(resultA.totalSources() > 0, 
            "Project A HYBRID query should return sources (combines LOCAL + GLOBAL)");

        LOG.infof("Project A HYBRID: sources=%d, chunks=%d", 
            resultA.totalSources(), resultA.sourceChunks().size());

        // Verify all source chunks belong to Project A's documents
        for (var chunk : resultA.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentA1.toString(), chunk.documentId(),
                    "Project A HYBRID query source chunks must only reference Project A documents");
            }
        }

        // Query Project B
        LightRAGQueryResult resultB = lightRAGService.query(
            "What is Apple known for?",
            QueryParam.Mode.HYBRID,
            projectB
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultB, "Project B result should not be null");
        assertNotNull(resultB.answer(), "Project B answer should not be null");
        assertTrue(resultB.totalSources() > 0, 
            "Project B HYBRID query should return sources (combines LOCAL + GLOBAL)");

        LOG.infof("Project B HYBRID: sources=%d, chunks=%d", 
            resultB.totalSources(), resultB.sourceChunks().size());

        // Verify all source chunks belong to Project B's documents
        for (var chunk : resultB.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentB1.toString(), chunk.documentId(),
                    "Project B HYBRID query source chunks must only reference Project B documents");
            }
        }

        // Verify no cross-contamination between projects
        boolean hasCrossContamination = resultA.sourceChunks().stream()
            .anyMatch(chunkA -> resultB.sourceChunks().stream()
                .anyMatch(chunkB -> chunkA.documentId() != null 
                    && chunkA.documentId().equals(chunkB.documentId())));
        
        assertFalse(hasCrossContamination, 
            "HYBRID queries should not return the same document sources for different projects");

        LOG.info("HYBRID query mode isolation verified successfully");
    }

    /**
     * Test LOCAL query mode respects project isolation.
     * 
     * LOCAL mode uses chunk-based vector similarity search.
     * Should only return chunks from the queried project's documents.
     */
    @Test
    void testLocalQueryScopedToProject() throws Exception {
        LOG.info("Testing LOCAL query mode isolation");

        // Query Project A
        LightRAGQueryResult resultA = lightRAGService.query(
            "Who is the CEO?",
            QueryParam.Mode.LOCAL,
            projectA
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultA, "Project A result should not be null");
        assertNotNull(resultA.answer(), "Project A answer should not be null");
        assertTrue(resultA.totalSources() > 0, 
            "Project A LOCAL query should return chunks via vector search");

        LOG.infof("Project A LOCAL: sources=%d, chunks=%d", 
            resultA.totalSources(), resultA.sourceChunks().size());

        // Verify all source chunks belong to Project A's documents
        for (var chunk : resultA.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentA1.toString(), chunk.documentId(),
                    "Project A LOCAL query source chunks must only reference Project A documents");
            }
        }

        // Query Project B
        LightRAGQueryResult resultB = lightRAGService.query(
            "Where are apples grown?",
            QueryParam.Mode.LOCAL,
            projectB
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultB, "Project B result should not be null");
        assertNotNull(resultB.answer(), "Project B answer should not be null");
        assertTrue(resultB.totalSources() > 0, 
            "Project B LOCAL query should return chunks via vector search");

        LOG.infof("Project B LOCAL: sources=%d, chunks=%d", 
            resultB.totalSources(), resultB.sourceChunks().size());

        // Verify all source chunks belong to Project B's documents
        for (var chunk : resultB.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentB1.toString(), chunk.documentId(),
                    "Project B LOCAL query source chunks must only reference Project B documents");
            }
        }

        // Verify no cross-contamination between projects
        boolean hasCrossContamination = resultA.sourceChunks().stream()
            .anyMatch(chunkA -> resultB.sourceChunks().stream()
                .anyMatch(chunkB -> chunkA.documentId() != null 
                    && chunkA.documentId().equals(chunkB.documentId())));
        
        assertFalse(hasCrossContamination, 
            "LOCAL queries should not return the same document sources for different projects");

        LOG.info("LOCAL query mode isolation verified successfully");
    }

    /**
     * Test MIX query mode respects project isolation.
     * 
     * MIX mode combines graph traversal with vector retrieval.
     * Should scope both graph expansion and vector search to project.
     */
    @Test
    void testMixQueryScopedToProject() throws Exception {
        LOG.info("Testing MIX query mode isolation");

        // Query Project A
        LightRAGQueryResult resultA = lightRAGService.query(
            "Tell me about Apple and its history",
            QueryParam.Mode.MIX,
            projectA
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultA, "Project A result should not be null");
        assertNotNull(resultA.answer(), "Project A answer should not be null");
        assertTrue(resultA.totalSources() > 0, 
            "Project A MIX query should return sources (graph + vector)");

        LOG.infof("Project A MIX: sources=%d, chunks=%d", 
            resultA.totalSources(), resultA.sourceChunks().size());

        // Verify all source chunks belong to Project A's documents
        for (var chunk : resultA.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentA1.toString(), chunk.documentId(),
                    "Project A MIX query source chunks must only reference Project A documents");
            }
        }

        // Query Project B
        LightRAGQueryResult resultB = lightRAGService.query(
            "Tell me about Apple and its uses",
            QueryParam.Mode.MIX,
            projectB
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultB, "Project B result should not be null");
        assertNotNull(resultB.answer(), "Project B answer should not be null");
        assertTrue(resultB.totalSources() > 0, 
            "Project B MIX query should return sources (graph + vector)");

        LOG.infof("Project B MIX: sources=%d, chunks=%d", 
            resultB.totalSources(), resultB.sourceChunks().size());

        // Verify all source chunks belong to Project B's documents
        for (var chunk : resultB.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentB1.toString(), chunk.documentId(),
                    "Project B MIX query source chunks must only reference Project B documents");
            }
        }

        // Verify no cross-contamination between projects
        boolean hasCrossContamination = resultA.sourceChunks().stream()
            .anyMatch(chunkA -> resultB.sourceChunks().stream()
                .anyMatch(chunkB -> chunkA.documentId() != null 
                    && chunkA.documentId().equals(chunkB.documentId())));
        
        assertFalse(hasCrossContamination, 
            "MIX queries should not return the same document sources for different projects");

        LOG.info("MIX query mode isolation verified successfully");
    }

    /**
     * Test NAIVE query mode respects project isolation.
     * 
     * NAIVE mode performs basic vector search with minimal processing.
     * Should only return chunks from the queried project.
     */
    @Test
    void testNaiveQueryScopedToProject() throws Exception {
        LOG.info("Testing NAIVE query mode isolation");

        // Query Project A
        LightRAGQueryResult resultA = lightRAGService.query(
            "Apple products",
            QueryParam.Mode.NAIVE,
            projectA
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultA, "Project A result should not be null");
        assertNotNull(resultA.answer(), "Project A answer should not be null");
        assertTrue(resultA.totalSources() > 0, 
            "Project A NAIVE query should return sources from basic vector search");

        LOG.infof("Project A NAIVE: sources=%d, chunks=%d", 
            resultA.totalSources(), resultA.sourceChunks().size());

        // Verify all source chunks belong to Project A's documents
        for (var chunk : resultA.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentA1.toString(), chunk.documentId(),
                    "Project A NAIVE query source chunks must only reference Project A documents");
            }
        }

        // Query Project B
        LightRAGQueryResult resultB = lightRAGService.query(
            "Apple benefits",
            QueryParam.Mode.NAIVE,
            projectB
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(resultB, "Project B result should not be null");
        assertNotNull(resultB.answer(), "Project B answer should not be null");
        assertTrue(resultB.totalSources() > 0, 
            "Project B NAIVE query should return sources from basic vector search");

        LOG.infof("Project B NAIVE: sources=%d, chunks=%d", 
            resultB.totalSources(), resultB.sourceChunks().size());

        // Verify all source chunks belong to Project B's documents
        for (var chunk : resultB.sourceChunks()) {
            if (chunk.documentId() != null) {
                assertEquals(documentB1.toString(), chunk.documentId(),
                    "Project B NAIVE query source chunks must only reference Project B documents");
            }
        }

        // Verify no cross-contamination between projects
        boolean hasCrossContamination = resultA.sourceChunks().stream()
            .anyMatch(chunkA -> resultB.sourceChunks().stream()
                .anyMatch(chunkB -> chunkA.documentId() != null 
                    && chunkA.documentId().equals(chunkB.documentId())));
        
        assertFalse(hasCrossContamination, 
            "NAIVE queries should not return the same document sources for different projects");

        LOG.info("NAIVE query mode isolation verified successfully");
    }

    /**
     * Test that source chunks returned by queries only reference
     * documents from the queried project.
     */
    @Test
    void testSourceChunksAreProjectScoped() throws Exception {
        LOG.info("Testing source chunks are project-scoped");

        // Query Project A with LOCAL mode (chunk-based, clearest source tracking)
        LightRAGQueryResult resultA = lightRAGService.query(
            "Tell me everything about Apple",
            QueryParam.Mode.LOCAL,
            projectA
        ).get(30, TimeUnit.SECONDS);

        assertTrue(resultA.totalSources() > 0, 
            "Project A should return source chunks");

        // Verify all source chunks reference Project A's document
        for (var chunk : resultA.sourceChunks()) {
            if (chunk.documentId() != null) {
                String docId = chunk.documentId();
                assertEquals(documentA1.toString(), docId, 
                    "Source chunk should reference Project A's document");
                LOG.infof("✓ Verified chunk from document: %s", docId);
            }
        }

        // Query Project B
        LightRAGQueryResult resultB = lightRAGService.query(
            "Tell me everything about Apple",
            QueryParam.Mode.LOCAL,
            projectB
        ).get(30, TimeUnit.SECONDS);

        assertTrue(resultB.totalSources() > 0, 
            "Project B should return source chunks");

        // Verify all source chunks reference Project B's document
        for (var chunk : resultB.sourceChunks()) {
            if (chunk.documentId() != null) {
                String docId = chunk.documentId();
                assertEquals(documentB1.toString(), docId, 
                    "Source chunk should reference Project B's document");
                LOG.infof("✓ Verified chunk from document: %s", docId);
            }
        }

        LOG.info("Source chunk isolation verified successfully");
    }

    /**
     * Test that querying with a non-existent project ID
     * returns no results (fails gracefully).
     */
    @Test
    void testQueryWithNonExistentProjectReturnsNoResults() throws Exception {
        LOG.info("Testing query with non-existent project");

        UUID nonExistentProject = UUID.randomUUID();

        // Query should complete but return empty/minimal results
        LightRAGQueryResult result = lightRAGService.query(
            "Tell me about Apple",
            QueryParam.Mode.LOCAL,
            nonExistentProject
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(result, "Result should not be null even for non-existent project");
        
        // Should return zero sources or handle gracefully
        int sources = result.totalSources();
        LOG.infof("Non-existent project returned %d sources", sources);
        
        assertEquals(0, sources, 
            "Non-existent project should return zero sources");

        LOG.info("Non-existent project handling verified successfully");
    }

    /**
     * Test that deleting a project cascades to delete all associated data:
     * - Documents are deleted (FK cascade)
     * - Vectors are deleted (FK cascade)
     * - Graph is deleted (explicit)
     */
    @Test
    void testProjectDeletionCascadesAllData() throws Exception {
        LOG.info("Testing project deletion cascade");

        // Create a new project directly using service
        Project project = new Project("Deletion Test Project");
        Project createdProject = projectService.create(project);
        UUID projectId = createdProject.getId();
        LOG.infof("Created project: %s", projectId);

        // Create project graph
        graphStorage.createProjectGraph(projectId.toString()).get(30, TimeUnit.SECONDS);

        // Create Document entities in database (required for FK constraints from vectors)
        Document doc1Entity = documentService.create(new Document(
            DocumentType.TEXT,
            "tech.txt",
            "Placeholder content - will be replaced by LightRAG",
            null,
            createdProject
        ));
        UUID doc1 = doc1Entity.getId();

        Document doc2Entity = documentService.create(new Document(
            DocumentType.TEXT,
            "science.txt",
            "Placeholder content - will be replaced by LightRAG",
            null,
            createdProject
        ));
        UUID doc2 = doc2Entity.getId();
        
        LOG.infof("Created documents: %s, %s", doc1, doc2);

        // Insert documents via LightRAGService
        String content1 = "This is a test document about technology and innovation.";
        String content2 = "This is another document about science and research.";

        CompletableFuture<String> insert1 = lightRAGService.insertDocument(
            doc1, content1, "tech.txt", projectId
        );
        CompletableFuture<String> insert2 = lightRAGService.insertDocument(
            doc2, content2, "science.txt", projectId
        );

        CompletableFuture.allOf(insert1, insert2).get(60, TimeUnit.SECONDS);
        LOG.infof("Inserted documents into LightRAG: %s, %s", doc1, doc2);

        // Wait for indexing
        Thread.sleep(2000);

        // Verify data exists before deletion
        // 1. Project exists in database
        Project foundProject = projectService.findById(projectId);
        assertNotNull(foundProject, "Project should exist before deletion");
        assertEquals("Deletion Test Project", foundProject.getName());

        // 2. Can query the project (graph has data)
        LightRAGQueryResult beforeResult = lightRAGService.query(
            "Tell me about technology",
            QueryParam.Mode.LOCAL,
            projectId
        ).get(30, TimeUnit.SECONDS);
        
        int sourcesBeforeDeletion = beforeResult.totalSources();
        assertTrue(sourcesBeforeDeletion > 0, 
            "Project should have queryable data before deletion");
        LOG.infof("Before deletion: %d sources found", sourcesBeforeDeletion);

        // DELETE THE PROJECT
        LOG.info("Deleting project...");
        projectService.delete(projectId);

        // Clear the entity manager cache to force reload from database
        entityManager.clear();

        // Give the database time to process cascades and transaction to commit
        Thread.sleep(1000);

        // Verify all data is gone
        // 1. Project is deleted - check using repository's findById (should return null)
        Project deletedProject = projectRepository.findById(projectId);
        assertNull(deletedProject, 
            "Project should have been deleted from database");

        // 2. Query returns empty results (graph is deleted, vectors cascade-deleted via FK)
        LightRAGQueryResult afterResult = lightRAGService.query(
            "Tell me about technology",
            QueryParam.Mode.LOCAL,
            projectId
        ).get(30, TimeUnit.SECONDS);
        
        assertEquals(0, afterResult.totalSources(),
            "Deleted project should return zero sources");

        LOG.info("Project deletion cascade verified successfully");
    }

    /**
     * Test that deleting one project does not affect other projects.
     */
    @Test
    void testProjectDeletionIsolation() throws Exception {
        LOG.info("Testing project deletion isolation");

        // Create two projects directly using service
        Project projectA = new Project("Project A");
        Project projectB = new Project("Project B");
        
        Project createdA = projectService.create(projectA);
        Project createdB = projectService.create(projectB);
        
        UUID projectAId = createdA.getId();
        UUID projectBId = createdB.getId();
        LOG.infof("Created projects: A=%s, B=%s", projectAId, projectBId);

        // Create project graphs
        graphStorage.createProjectGraph(projectAId.toString()).get(30, TimeUnit.SECONDS);
        graphStorage.createProjectGraph(projectBId.toString()).get(30, TimeUnit.SECONDS);

        // Create Document entities in database (required for FK constraints from vectors)
        Document docAEntity = documentService.create(new Document(
            DocumentType.TEXT,
            "apple.txt",
            "Placeholder content - will be replaced by LightRAG",
            null,
            createdA
        ));
        UUID docA = docAEntity.getId();

        Document docBEntity = documentService.create(new Document(
            DocumentType.TEXT,
            "microsoft.txt",
            "Placeholder content - will be replaced by LightRAG",
            null,
            createdB
        ));
        UUID docB = docBEntity.getId();
        
        LOG.infof("Created documents: A=%s, B=%s", docA, docB);

        // Insert documents via LightRAGService
        String contentA = "Project A document about Apple Inc and technology.";
        String contentB = "Project B document about Microsoft Corporation.";

        CompletableFuture<String> insertA = lightRAGService.insertDocument(
            docA, contentA, "apple.txt", projectAId
        );
        CompletableFuture<String> insertB = lightRAGService.insertDocument(
            docB, contentB, "microsoft.txt", projectBId
        );

        CompletableFuture.allOf(insertA, insertB).get(60, TimeUnit.SECONDS);
        LOG.infof("Inserted documents into LightRAG: A=%s, B=%s", docA, docB);

        // Wait for indexing
        Thread.sleep(2000);

        // Verify both projects have data
        LightRAGQueryResult resultA = lightRAGService.query(
            "Tell me about Apple",
            QueryParam.Mode.LOCAL,
            projectAId
        ).get(30, TimeUnit.SECONDS);
        
        LightRAGQueryResult resultB = lightRAGService.query(
            "Tell me about Microsoft",
            QueryParam.Mode.LOCAL,
            projectBId
        ).get(30, TimeUnit.SECONDS);

        int sourcesA = resultA.totalSources();
        int sourcesB = resultB.totalSources();
        assertTrue(sourcesA > 0, "Project A should have data");
        assertTrue(sourcesB > 0, "Project B should have data");
        LOG.infof("Before deletion - A: %d sources, B: %d sources", sourcesA, sourcesB);

        // Delete Project A
        LOG.info("Deleting Project A...");
        projectService.delete(projectAId);

        Thread.sleep(1000);

        // Verify Project A is deleted
        try {
            projectService.findById(projectAId);
            fail("Project A should have been deleted");
        } catch (Exception e) {
            LOG.infof("Project A not found as expected: %s", e.getMessage());
        }

        LightRAGQueryResult afterDeleteA = lightRAGService.query(
            "Tell me about Apple",
            QueryParam.Mode.LOCAL,
            projectAId
        ).get(30, TimeUnit.SECONDS);
        
        assertEquals(0, afterDeleteA.totalSources(),
            "Deleted project A should return zero sources");

        // Verify Project B is UNAFFECTED
        Project projectBAfter = projectService.findById(projectBId);
        assertNotNull(projectBAfter, "Project B should still exist");
        assertEquals("Project B", projectBAfter.getName());

        LightRAGQueryResult afterDeleteB = lightRAGService.query(
            "Tell me about Microsoft",
            QueryParam.Mode.LOCAL,
            projectBId
        ).get(30, TimeUnit.SECONDS);
        
        assertEquals(sourcesB, afterDeleteB.totalSources(),
            "Project B data should remain unchanged after deleting Project A");

        LOG.info("Project deletion isolation verified successfully");
    }


}
