package br.edu.ifba.lightrag.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for EntitySimilarityCalculator.
 * 
 * Tests all eight similarity calculation methods using the examples
 * from research.md and requirements from spec.md.
 * 
 * Following TDD principles: These tests should FAIL initially
 * until implementation is complete.
 */
@QuarkusTest
class EntitySimilarityCalculatorTest {
    
    @Inject
    EntitySimilarityCalculator calculator;
    
    @Inject
    DeduplicationConfig config;
    
    // ========================================================================
    // Tests for normalizeName()
    // ========================================================================
    
    @Test
    @DisplayName("normalizeName should convert to lowercase")
    void testNormalizeNameLowercase() {
        String result = calculator.normalizeName("Warren State HOME");
        assertEquals("warren state home", result);
    }
    
    @Test
    @DisplayName("normalizeName should trim whitespace")
    void testNormalizeNameTrim() {
        String result = calculator.normalizeName("  Warren Home  ");
        assertEquals("warren home", result);
    }
    
    @Test
    @DisplayName("normalizeName should collapse multiple spaces")
    void testNormalizeNameCollapseSpaces() {
        String result = calculator.normalizeName("Warren    State     Home");
        assertEquals("warren state home", result);
    }
    
    @Test
    @DisplayName("normalizeName should remove punctuation")
    void testNormalizeNameRemovePunctuation() {
        String result = calculator.normalizeName("Warren's State-Home & Training.");
        assertEquals("warrens statehome  training", result);
    }
    
    @Test
    @DisplayName("normalizeName should handle combined normalization")
    void testNormalizeNameCombined() {
        String result = calculator.normalizeName("  Warren's   State-HOME  ");
        assertEquals("warrens statehome", result);
    }
    
    @Test
    @DisplayName("normalizeName should throw exception for null input")
    void testNormalizeNameNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.normalizeName(null);
        });
    }
    
    // ========================================================================
    // Tests for tokenize()
    // ========================================================================
    
    @Test
    @DisplayName("tokenize should split on whitespace")
    void testTokenizeSplitWhitespace() {
        var tokens = calculator.tokenize("Warren State Home");
        assertEquals(3, tokens.size());
        assertTrue(tokens.contains("Warren"));
        assertTrue(tokens.contains("State"));
        assertTrue(tokens.contains("Home"));
    }
    
    @Test
    @DisplayName("tokenize should handle normalized input")
    void testTokenizeNormalizedInput() {
        String normalized = calculator.normalizeName("Warren State Home");
        var tokens = calculator.tokenize(normalized);
        assertEquals(3, tokens.size());
        assertTrue(tokens.contains("warren"));
        assertTrue(tokens.contains("state"));
        assertTrue(tokens.contains("home"));
    }
    
    @Test
    @DisplayName("tokenize should handle single word")
    void testTokenizeSingleWord() {
        var tokens = calculator.tokenize("MIT");
        assertEquals(1, tokens.size());
        assertTrue(tokens.contains("MIT"));
    }
    
    @Test
    @DisplayName("tokenize should throw exception for null input")
    void testTokenizeNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.tokenize(null);
        });
    }
    
    // ========================================================================
    // Tests for computeJaccardSimilarity() - research.md lines 76-87
    // ========================================================================
    
    @Test
    @DisplayName("Jaccard: identical names should return 1.0")
    void testJaccardIdentical() {
        double score = calculator.computeJaccardSimilarity(
            "warren state home",
            "warren state home"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Jaccard: Warren State Home vs Warren Home = 0.667")
    void testJaccardWarrenExample() {
        // "warren state home" vs "warren home"
        // Intersection: {warren, home} = 2 tokens
        // Union: {warren, state, home} = 3 tokens
        // Jaccard = 2/3 = 0.667
        double score = calculator.computeJaccardSimilarity(
            "warren state home",
            "warren home"
        );
        assertEquals(0.667, score, 0.01);
    }
    
    @Test
    @DisplayName("Jaccard: completely different names should return 0.0")
    void testJaccardDifferent() {
        double score = calculator.computeJaccardSimilarity(
            "warren home",
            "springfield hospital"
        );
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Jaccard: word reordering should not affect score")
    void testJaccardReordering() {
        double score1 = calculator.computeJaccardSimilarity(
            "state home warren",
            "warren state home"
        );
        assertEquals(1.0, score1, 0.001);
    }
    
    @Test
    @DisplayName("Jaccard: empty strings should return 0.0")
    void testJaccardEmptyStrings() {
        double score = calculator.computeJaccardSimilarity("", "");
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Jaccard: null input should throw exception")
    void testJaccardNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeJaccardSimilarity(null, "test");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeJaccardSimilarity("test", null);
        });
    }
    
    // ========================================================================
    // Tests for computeContainmentScore()
    // ========================================================================
    
    @Test
    @DisplayName("Containment: substring should return 1.0")
    void testContainmentSubstring() {
        double score = calculator.computeContainmentScore(
            "warren state home",
            "warren"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Containment: MIT in Massachusetts Institute of Technology")
    void testContainmentMIT() {
        // "mit" should be contained in "massachusetts institute of technology"
        // after normalization
        double score = calculator.computeContainmentScore(
            "massachusetts institute of technology",
            "mit"
        );
        // Note: This tests containment as substring, not word-level
        // Implementation should check if shorter is substring of longer
        assertTrue(score >= 0.0 && score <= 1.0);
    }
    
    @Test
    @DisplayName("Containment: no containment should return 0.0")
    void testContainmentNoMatch() {
        double score = calculator.computeContainmentScore(
            "warren home",
            "springfield"
        );
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Containment: identical strings should return 1.0")
    void testContainmentIdentical() {
        double score = calculator.computeContainmentScore(
            "warren home",
            "warren home"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Containment: null input should throw exception")
    void testContainmentNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeContainmentScore(null, "test");
        });
    }
    
    // ========================================================================
    // Tests for computeLevenshteinSimilarity() - research.md lines 54-74
    // ========================================================================
    
    @Test
    @DisplayName("Levenshtein: identical strings should return 1.0")
    void testLevenshteinIdentical() {
        double score = calculator.computeLevenshteinSimilarity(
            "warren home",
            "warren home"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Levenshtein: one character difference")
    void testLevenshteinOneChar() {
        // "warren" vs "warran" - edit distance = 1
        // max length = 6
        // similarity = 1 - (1/6) = 0.833
        double score = calculator.computeLevenshteinSimilarity(
            "warren",
            "warran"
        );
        assertEquals(0.833, score, 0.01);
    }
    
    @Test
    @DisplayName("Levenshtein: typo handling - Microsft vs Microsoft")
    void testLevenshteinTypo() {
        // "microsft" vs "microsoft" - edit distance = 1 (insertion)
        // max length = 9
        // similarity = 1 - (1/9) = 0.889
        double score = calculator.computeLevenshteinSimilarity(
            "microsft",
            "microsoft"
        );
        assertEquals(0.889, score, 0.01);
    }
    
    @Test
    @DisplayName("Levenshtein: completely different strings")
    void testLevenshteinDifferent() {
        double score = calculator.computeLevenshteinSimilarity(
            "abc",
            "xyz"
        );
        // edit distance = 3, max length = 3
        // similarity = 1 - (3/3) = 0.0
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Levenshtein: empty strings should return 1.0")
    void testLevenshteinEmpty() {
        double score = calculator.computeLevenshteinSimilarity("", "");
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Levenshtein: null input should throw exception")
    void testLevenshteinNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeLevenshteinSimilarity(null, "test");
        });
    }
    
    // ========================================================================
    // Tests for computeAbbreviationScore() - research.md lines 89-107
    // ========================================================================
    
    @Test
    @DisplayName("Abbreviation: MIT matches Massachusetts Institute of Technology")
    void testAbbreviationMIT() {
        double score = calculator.computeAbbreviationScore(
            "Massachusetts Institute of Technology",
            "MIT"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Abbreviation: IBM matches International Business Machines")
    void testAbbreviationIBM() {
        double score = calculator.computeAbbreviationScore(
            "International Business Machines",
            "IBM"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Abbreviation: reversed parameters should still match")
    void testAbbreviationReversed() {
        double score = calculator.computeAbbreviationScore(
            "MIT",
            "Massachusetts Institute of Technology"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Abbreviation: no match should return 0.0")
    void testAbbreviationNoMatch() {
        double score = calculator.computeAbbreviationScore(
            "Warren State Home",
            "MIT"
        );
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Abbreviation: lowercase abbreviations should match")
    void testAbbreviationLowercase() {
        double score = calculator.computeAbbreviationScore(
            "Massachusetts Institute of Technology",
            "mit"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("Abbreviation: null input should throw exception")
    void testAbbreviationNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeAbbreviationScore(null, "test");
        });
    }
    
    // ========================================================================
    // Tests for computeNameSimilarity() - Weighted combination
    // ========================================================================
    
    @Test
    @DisplayName("computeNameSimilarity: identical names should return 1.0")
    void testNameSimilarityIdentical() {
        double score = calculator.computeNameSimilarity(
            "Warren State Home",
            "Warren State Home",
            "organization",
            "organization"
        );
        assertEquals(1.0, score, 0.001);
    }
    
    @Test
    @DisplayName("computeNameSimilarity: different types should return 0.0")
    void testNameSimilarityDifferentTypes() {
        // Type-aware matching: never merge different types
        double score = calculator.computeNameSimilarity(
            "Apple",
            "Apple",
            "organization",
            "fruit"
        );
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    @DisplayName("computeNameSimilarity: Warren variations with weighted score")
    void testNameSimilarityWarrenWeighted() {
        // "Warren State Home" vs "Warren Home"
        // Expected metrics (approx):
        // - Jaccard: 0.667 (2/3 tokens match)
        // - Containment: 1.0 ("warren home" in "warren state home")
        // - Levenshtein: ~0.85 (high similarity)
        // - Abbreviation: 0.0 (not an abbreviation)
        // Weighted = 0.35*0.667 + 0.25*1.0 + 0.30*0.85 + 0.10*0.0
        //          = 0.233 + 0.25 + 0.255 + 0 = 0.738
        double score = calculator.computeNameSimilarity(
            "Warren State Home",
            "Warren Home",
            "organization",
            "organization"
        );
        assertTrue(score >= 0.70 && score <= 0.80, 
            "Expected weighted score ~0.74, got " + score);
    }
    
    @Test
    @DisplayName("computeNameSimilarity: MIT abbreviation with weighted score")
    void testNameSimilarityMITWeighted() {
        // "Massachusetts Institute of Technology" vs "MIT"
        // Expected high score due to abbreviation match (weight 0.10)
        // and containment (weight 0.25)
        double score = calculator.computeNameSimilarity(
            "Massachusetts Institute of Technology",
            "MIT",
            "organization",
            "organization"
        );
        assertTrue(score > 0.30, "Expected score > 0.30 for MIT abbreviation");
    }
    
    @ParameterizedTest
    @CsvSource({
        "'', 'test', IllegalArgumentException",
        "'test', '', IllegalArgumentException",
        "'  ', 'test', IllegalArgumentException"
    })
    @DisplayName("computeNameSimilarity: blank names should throw exception")
    void testNameSimilarityBlankNames(String name1, String name2) {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeNameSimilarity(name1, name2, "type1", "type2");
        });
    }
    
    @Test
    @DisplayName("computeNameSimilarity: null types should throw exception")
    void testNameSimilarityNullTypes() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeNameSimilarity("name1", "name2", null, "type2");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeNameSimilarity("name1", "name2", "type1", null);
        });
    }
    
    // ========================================================================
    // Tests for computeSimilarity() - Entity-level with EntitySimilarityScore
    // ========================================================================
    
    @Test
    @DisplayName("computeSimilarity: should return detailed score breakdown")
    void testComputeSimilarityBreakdown() {
        Entity entity1 = new Entity(
            "Warren State Home",
            "organization",
            "A residential facility",
            "source1",
            null
        );
        Entity entity2 = new Entity(
            "Warren Home",
            "organization",
            "A residential facility",
            "source2",
            null
        );
        
        EntitySimilarityScore score = calculator.computeSimilarity(entity1, entity2);
        
        assertNotNull(score);
        assertTrue(score.jaccardScore() >= 0.0 && score.jaccardScore() <= 1.0);
        assertTrue(score.containmentScore() >= 0.0 && score.containmentScore() <= 1.0);
        assertTrue(score.levenshteinScore() >= 0.0 && score.levenshteinScore() <= 1.0);
        assertTrue(score.abbreviationScore() >= 0.0 && score.abbreviationScore() <= 1.0);
        assertTrue(score.finalScore() >= 0.0 && score.finalScore() <= 1.0);
    }
    
    @Test
    @DisplayName("computeSimilarity: weighted score should match formula")
    void testComputeSimilarityWeightedFormula() {
        Entity entity1 = new Entity(
            "Warren State Home",
            "organization",
            "Description 1",
            "source1",
            null
        );
        Entity entity2 = new Entity(
            "Warren Home",
            "organization",
            "Description 2",
            "source2",
            null
        );
        
        EntitySimilarityScore score = calculator.computeSimilarity(entity1, entity2);
        
        // Verify weighted score matches formula
        double expectedWeighted = 
            config.weightJaccard() * score.jaccardScore() +
            config.weightContainment() * score.containmentScore() +
            config.weightEdit() * score.levenshteinScore() +
            config.weightAbbreviation() * score.abbreviationScore();
        
        assertEquals(expectedWeighted, score.finalScore(), 0.001);
    }
    
    @Test
    @DisplayName("computeSimilarity: different types should return 0.0 weighted score")
    void testComputeSimilarityDifferentTypes() {
        Entity entity1 = new Entity(
            "Apple",
            "organization",
            "Tech company",
            "source1",
            null
        );
        Entity entity2 = new Entity(
            "Apple",
            "fruit",
            "A red fruit",
            "source2",
            null
        );
        
        EntitySimilarityScore score = calculator.computeSimilarity(entity1, entity2);
        
        assertEquals(0.0, score.finalScore(), 0.001);
    }
    
    @Test
    @DisplayName("computeSimilarity: null entity should throw exception")
    void testComputeSimilarityNullEntity() {
        Entity entity = new Entity("Test", "type", "desc", "source", null);
        
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeSimilarity(null, entity);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.computeSimilarity(entity, null);
        });
    }
    
    // ========================================================================
    // Integration tests with real examples from research.md
    // ========================================================================
    
    @Test
    @DisplayName("Integration: Warren Home cluster should have high similarity")
    void testIntegrationWarrenCluster() {
        // From research.md - these should all be similar enough to cluster
        String[] warrenVariations = {
            "Warren State Home",
            "Warren Home",
            "Warren State Home and Training School",
            "Warren Home School"
        };
        
        // All pairs should exceed similarity threshold (0.75)
        for (int i = 0; i < warrenVariations.length; i++) {
            for (int j = i + 1; j < warrenVariations.length; j++) {
                double score = calculator.computeNameSimilarity(
                    warrenVariations[i],
                    warrenVariations[j],
                    "organization",
                    "organization"
                );
                assertTrue(score >= config.similarityThreshold(),
                    String.format("%s vs %s: score %.3f should be >= %.3f",
                        warrenVariations[i], warrenVariations[j], 
                        score, config.similarityThreshold()));
            }
        }
    }
    
    @Test
    @DisplayName("Integration: Apple Inc vs apple fruit should NOT merge")
    void testIntegrationAppleTypeSafety() {
        double score = calculator.computeNameSimilarity(
            "Apple",
            "Apple",
            "organization",
            "fruit"
        );
        assertEquals(0.0, score, 0.001, 
            "Different types should never have similarity > 0");
    }
    
    @Test
    @DisplayName("Integration: Person name variations")
    void testIntegrationPersonNames() {
        // From research.md requirements - person name variations
        String[] personVariations = {
            "Dr. Elizabeth Bennett",
            "Elizabeth Bennett",
            "Dr. E. Bennett"
        };
        
        // These should have reasonable similarity
        for (int i = 0; i < personVariations.length; i++) {
            for (int j = i + 1; j < personVariations.length; j++) {
                double score = calculator.computeNameSimilarity(
                    personVariations[i],
                    personVariations[j],
                    "person",
                    "person"
                );
                assertTrue(score > 0.5,
                    String.format("%s vs %s: score %.3f should be > 0.5",
                        personVariations[i], personVariations[j], score));
            }
        }
    }
}
