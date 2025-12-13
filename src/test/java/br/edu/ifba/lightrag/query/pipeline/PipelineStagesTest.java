package br.edu.ifba.lightrag.query.pipeline;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGQueryResult.SourceChunk;
import br.edu.ifba.lightrag.core.QueryParam;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.query.ContextItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the query pipeline stages.
 * 
 * <p>Tests cover all four stages of the pipeline:</p>
 * <ul>
 *   <li>TruncateStage - Token budget management</li>
 *   <li>MergeStage - Round-robin context merging</li>
 *   <li>ContextBuilderStage - Final prompt construction</li>
 * </ul>
 * 
 * <p>Note: ChunkSearchStage and EntitySearchStage require storage mocks
 * and are tested separately in integration tests.</p>
 */
class PipelineStagesTest {
    
    private PipelineContext context;
    
    @BeforeEach
    void setUp() {
        QueryParam param = QueryParam.builder()
                .projectId("test-project")
                .mode(QueryParam.Mode.HYBRID)
                .responseType("Multiple Paragraphs")
                .build();
        context = new PipelineContext("What is machine learning?", param, null);
    }
    
    // ========================================================================
    // TruncateStage Tests
    // ========================================================================
    
    @Nested
    @DisplayName("TruncateStage")
    class TruncateStageTests {
        
        private TruncateStage stage;
        
        @BeforeEach
        void setUp() {
            // Use small token budget for testing (400 tokens total)
            stage = new TruncateStage(400, 0.3, 0.4, 0.3);
        }
        
        @Test
        @DisplayName("should skip when no candidates exist")
        void shouldSkipWhenNoCandidates() {
            assertTrue(stage.shouldSkip(context));
        }
        
        @Test
        @DisplayName("should not skip when candidates exist")
        void shouldNotSkipWhenCandidatesExist() {
            context.addChunkCandidate(createChunk("chunk1", "Some text"));
            assertFalse(stage.shouldSkip(context));
        }
        
        @Test
        @DisplayName("should truncate chunks within budget")
        void shouldTruncateChunksWithinBudget() throws Exception {
            // Add chunks that will exceed 120 token budget (30% of 400)
            // Each ~50 word chunk is approximately 50-70 tokens
            String longText = "This is a fairly long chunk of text that contains many words " +
                    "and should contribute a significant number of tokens to the overall count. " +
                    "We need to ensure that the budget is exceeded so that truncation occurs. " +
                    "Adding more and more text here to make sure we get enough tokens. ";
            context.setChunkCandidates(List.of(
                    createChunk("c1", longText + "First chunk content."),
                    createChunk("c2", longText + "Second chunk content."),
                    createChunk("c3", longText + "Third chunk content."),
                    createChunk("c4", longText + "Fourth chunk content.")
            ));
            
            PipelineContext result = stage.process(context).get();
            
            // Should have some chunks but not all (budget is only 120 tokens)
            assertTrue(result.getTruncatedChunks().size() < 4, 
                    "Expected fewer than 4 chunks due to budget, got " + result.getTruncatedChunks().size());
            assertTrue(result.getChunkTokens() <= 120,
                    "Expected <= 120 chunk tokens, got " + result.getChunkTokens());
        }
        
        @Test
        @DisplayName("should truncate entities within budget")
        void shouldTruncateEntitiesWithinBudget() throws Exception {
            // Add entities that will exceed 160 token budget (40% of 400)
            context.setEntityCandidates(List.of(
                    createEntity("Entity1", "PERSON", "Short description"),
                    createEntity("Entity2", "ORGANIZATION", "Another description"),
                    createEntity("Entity3", "LOCATION", "A very long description that contains " +
                            "many words and should contribute significantly to the token count"),
                    createEntity("Entity4", "CONCEPT", "Yet another description")
            ));
            
            PipelineContext result = stage.process(context).get();
            
            assertTrue(result.getTruncatedEntities().size() >= 1);
            assertTrue(result.getEntityTokens() <= 160);
        }
        
        @Test
        @DisplayName("should truncate relations within budget")
        void shouldTruncateRelationsWithinBudget() throws Exception {
            // Add relations that will exceed 120 token budget (30% of 400)
            context.setRelationCandidates(List.of(
                    createRelation("A", "B", "A is connected to B"),
                    createRelation("B", "C", "B leads to C"),
                    createRelation("C", "D", "C is related to D through a complex relationship " +
                            "that involves multiple factors and considerations")
            ));
            
            PipelineContext result = stage.process(context).get();
            
            assertTrue(result.getTruncatedRelations().size() >= 1);
            assertTrue(result.getRelationTokens() <= 120);
        }
        
        @Test
        @DisplayName("should calculate total tokens correctly")
        void shouldCalculateTotalTokensCorrectly() throws Exception {
            context.setChunkCandidates(List.of(createChunk("c1", "Chunk text")));
            context.setEntityCandidates(List.of(createEntity("E1", "TYPE", "Description")));
            context.setRelationCandidates(List.of(createRelation("X", "Y", "Relationship")));
            
            PipelineContext result = stage.process(context).get();
            
            assertEquals(
                    result.getChunkTokens() + result.getEntityTokens() + result.getRelationTokens(),
                    result.getTotalTokens()
            );
        }
        
        @Test
        @DisplayName("should return stage name correctly")
        void shouldReturnStageName() {
            assertEquals("truncate", stage.getName());
        }
    }
    
    // ========================================================================
    // MergeStage Tests
    // ========================================================================
    
    @Nested
    @DisplayName("MergeStage")
    class MergeStageTests {
        
        private MergeStage stage;
        
        @BeforeEach
        void setUp() {
            stage = new MergeStage(4000);
        }
        
        @Test
        @DisplayName("should skip when no truncated items exist")
        void shouldSkipWhenNoTruncatedItems() {
            assertTrue(stage.shouldSkip(context));
        }
        
        @Test
        @DisplayName("should not skip when truncated items exist")
        void shouldNotSkipWhenTruncatedItemsExist() {
            context.setTruncatedChunks(List.of(
                    ContextItem.chunk("c1", "content", null, 10)
            ));
            assertFalse(stage.shouldSkip(context));
        }
        
        @Test
        @DisplayName("should merge items in round-robin order")
        void shouldMergeInRoundRobinOrder() throws Exception {
            context.setTruncatedEntities(List.of(
                    ContextItem.entity("E1", "Entity 1 content", 10),
                    ContextItem.entity("E2", "Entity 2 content", 10)
            ));
            context.setTruncatedRelations(List.of(
                    ContextItem.relation("A", "B", "Relation 1", 10)
            ));
            context.setTruncatedChunks(List.of(
                    ContextItem.chunk("C1", "Chunk 1 content", null, 10),
                    ContextItem.chunk("C2", "Chunk 2 content", null, 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            List<ContextItem> merged = result.getMergedItems();
            
            // Should have all 5 items
            assertEquals(5, merged.size());
            
            // First round: E1, R1, C1
            assertEquals("entity", merged.get(0).type());
            assertEquals("relation", merged.get(1).type());
            assertEquals("chunk", merged.get(2).type());
            
            // Second round: E2, C2
            assertEquals("entity", merged.get(3).type());
            assertEquals("chunk", merged.get(4).type());
        }
        
        @Test
        @DisplayName("should use chunk-first order when configured")
        void shouldUseChunkFirstOrderWhenConfigured() throws Exception {
            stage = new MergeStage(MergeStage.MergeOrder.CHUNK_ENTITY_RELATION);
            
            context.setTruncatedEntities(List.of(
                    ContextItem.entity("E1", "Entity", 10)
            ));
            context.setTruncatedRelations(List.of(
                    ContextItem.relation("A", "B", "Relation", 10)
            ));
            context.setTruncatedChunks(List.of(
                    ContextItem.chunk("C1", "Chunk", null, 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            List<ContextItem> merged = result.getMergedItems();
            
            // Chunk should be first
            assertEquals("chunk", merged.get(0).type());
            assertEquals("entity", merged.get(1).type());
            assertEquals("relation", merged.get(2).type());
        }
        
        @Test
        @DisplayName("should set final context")
        void shouldSetFinalContext() throws Exception {
            context.setTruncatedChunks(List.of(
                    ContextItem.chunk("c1", "Test content", null, 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            
            assertFalse(result.getFinalContext().isEmpty());
            assertTrue(result.getFinalContext().contains("Test content"));
        }
        
        @Test
        @DisplayName("should return stage name correctly")
        void shouldReturnStageName() {
            assertEquals("merge", stage.getName());
        }
        
        @Test
        @DisplayName("should expose merge order")
        void shouldExposeMergeOrder() {
            assertEquals(MergeStage.MergeOrder.ENTITY_RELATION_CHUNK, stage.getMergeOrder());
        }
    }
    
    // ========================================================================
    // ContextBuilderStage Tests
    // ========================================================================
    
    @Nested
    @DisplayName("ContextBuilderStage")
    class ContextBuilderStageTests {
        
        private ContextBuilderStage stage;
        
        @BeforeEach
        void setUp() {
            stage = new ContextBuilderStage();
        }
        
        @Test
        @DisplayName("should never skip (always needs to build prompt)")
        void shouldNeverSkip() {
            assertFalse(stage.shouldSkip(context));
        }
        
        @Test
        @DisplayName("should include section headers by default")
        void shouldIncludeHeadersByDefault() throws Exception {
            context.setMergedItems(List.of(
                    ContextItem.entity("E1", "Entity content", 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            assertTrue(prompt.contains("## Context"));
            assertTrue(prompt.contains("### Entities"));
            assertTrue(prompt.contains("## Query"));
        }
        
        @Test
        @DisplayName("should group items by type")
        void shouldGroupItemsByType() throws Exception {
            context.setMergedItems(List.of(
                    ContextItem.chunk("C1", "Chunk content", null, 10),
                    ContextItem.entity("E1", "Entity content", 10),
                    ContextItem.relation("A", "B", "Relation content", 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            // All sections should be present
            assertTrue(prompt.contains("### Entities"));
            assertTrue(prompt.contains("### Relations"));
            assertTrue(prompt.contains("### Sources"));
        }
        
        @Test
        @DisplayName("should include query")
        void shouldIncludeQuery() throws Exception {
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            assertTrue(prompt.contains("What is machine learning?"));
        }
        
        @Test
        @DisplayName("should include response type instruction")
        void shouldIncludeResponseTypeInstruction() throws Exception {
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            assertTrue(prompt.contains("Please respond with: Multiple Paragraphs"));
        }
        
        @Test
        @DisplayName("should include conversation history when present")
        void shouldIncludeConversationHistory() throws Exception {
            QueryParam paramWithHistory = QueryParam.builder()
                    .projectId("test-project")
                    .mode(QueryParam.Mode.HYBRID)
                    .addConversationMessage("user", "Previous question")
                    .addConversationMessage("assistant", "Previous answer")
                    .build();
            context = new PipelineContext("Follow-up question", paramWithHistory, null);
            
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            assertTrue(prompt.contains("## Conversation History"));
            assertTrue(prompt.contains("User: Previous question"));
            assertTrue(prompt.contains("Assistant: Previous answer"));
        }
        
        @Test
        @DisplayName("should include custom system prompt when provided")
        void shouldIncludeCustomSystemPrompt() throws Exception {
            stage = new ContextBuilderStage("You are a helpful assistant.");
            
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            assertTrue(prompt.startsWith("You are a helpful assistant."));
        }
        
        @Test
        @DisplayName("should not include headers when disabled")
        void shouldNotIncludeHeadersWhenDisabled() throws Exception {
            stage = new ContextBuilderStage(false, true, null);
            context.setMergedItems(List.of(
                    ContextItem.entity("E1", "Entity content", 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            assertFalse(prompt.contains("## Context"));
            assertFalse(prompt.contains("### Entities"));
        }
        
        @Test
        @DisplayName("should use flat format when groupByType disabled")
        void shouldUseFlatFormatWhenGroupByTypeDisabled() throws Exception {
            stage = new ContextBuilderStage(true, false, null);
            context.setMergedItems(List.of(
                    ContextItem.entity("E1", "Entity content", 10),
                    ContextItem.chunk("C1", "Chunk content", null, 10)
            ));
            
            PipelineContext result = stage.process(context).get();
            String prompt = result.getFinalPrompt();
            
            // Should have type prefixes instead of headers
            assertTrue(prompt.contains("[Entity]"));
            assertTrue(prompt.contains("[Source]"));
        }
        
        @Test
        @DisplayName("should return stage name correctly")
        void shouldReturnStageName() {
            assertEquals("context-builder", stage.getName());
        }
    }
    
    // ========================================================================
    // PipelineContext Tests
    // ========================================================================
    
    @Nested
    @DisplayName("PipelineContext")
    class PipelineContextTests {
        
        @Test
        @DisplayName("should store and retrieve query")
        void shouldStoreAndRetrieveQuery() {
            assertEquals("What is machine learning?", context.getQuery());
        }
        
        @Test
        @DisplayName("should store and retrieve project ID")
        void shouldStoreAndRetrieveProjectId() {
            assertEquals("test-project", context.getProjectId());
        }
        
        @Test
        @DisplayName("should report hasCandidates correctly")
        void shouldReportHasCandidatesCorrectly() {
            assertFalse(context.hasCandidates());
            
            context.addChunkCandidate(createChunk("c1", "text"));
            assertTrue(context.hasCandidates());
        }
        
        @Test
        @DisplayName("should calculate total candidate count")
        void shouldCalculateTotalCandidateCount() {
            context.addChunkCandidate(createChunk("c1", "text"));
            context.addEntityCandidate(createEntity("e1", "TYPE", "desc"));
            context.addRelationCandidate(createRelation("a", "b", "desc"));
            
            assertEquals(3, context.getTotalCandidateCount());
        }
        
        @Test
        @DisplayName("should store custom attributes")
        void shouldStoreCustomAttributes() {
            context.setAttribute("customKey", "customValue");
            
            assertEquals("customValue", context.getAttribute("customKey", String.class));
            assertTrue(context.hasAttribute("customKey"));
        }
        
        @Test
        @DisplayName("should throw on attribute type mismatch")
        void shouldThrowOnAttributeTypeMismatch() {
            context.setAttribute("key", "string value");
            
            assertThrows(ClassCastException.class, () ->
                    context.getAttribute("key", Integer.class)
            );
        }
        
        @Test
        @DisplayName("should report hasContext correctly")
        void shouldReportHasContextCorrectly() {
            assertFalse(context.hasContext());
            
            context.setFinalContext("Some context");
            assertTrue(context.hasContext());
        }
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private SourceChunk createChunk(String id, String content) {
        return new SourceChunk(id, content, 0.9, null, null, 0, "chunk");
    }
    
    private Entity createEntity(String name, String type, String description) {
        return Entity.builder()
                .entityName(name)
                .entityType(type)
                .description(description)
                .build();
    }
    
    private Relation createRelation(String src, String tgt, String description) {
        return Relation.builder()
                .srcId(src)
                .tgtId(tgt)
                .description(description)
                .keywords("test")
                .build();
    }
}
