package br.edu.ifba.lightrag.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for EntitySimilarityScore.
 * 
 * Tests record validation, helper methods, and edge cases.
 * This is a simple record class, so tests focus on:
 * - Constructor validation
 * - isDuplicate() method
 * - hasSameType() method
 * - toLogString() formatting
 * 
 * Following TDD principles: These tests should PASS immediately
 * since EntitySimilarityScore is already fully implemented as a record.
 */
class EntitySimilarityScoreTest {
    
    // ========================================================================
    // Tests for Record Validation
    // ========================================================================
    
    @Test
    @DisplayName("EntitySimilarityScore should create valid instance with all scores in range")
    void testCreateValidInstance() {
        // Act
        EntitySimilarityScore score = new EntitySimilarityScore(
            "MIT", "Massachusetts Institute of Technology",
            "ORGANIZATION", "ORGANIZATION",
            0.5, 0.7, 0.8, 0.9, 0.75
        );
        
        // Assert
        assertNotNull(score);
        assertEquals("MIT", score.entity1Name());
        assertEquals("Massachusetts Institute of Technology", score.entity2Name());
        assertEquals("ORGANIZATION", score.entity1Type());
        assertEquals("ORGANIZATION", score.entity2Type());
        assertEquals(0.5, score.jaccardScore());
        assertEquals(0.7, score.containmentScore());
        assertEquals(0.8, score.levenshteinScore());
        assertEquals(0.9, score.abbreviationScore());
        assertEquals(0.75, score.finalScore());
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should accept zero scores")
    void testCreateInstanceWithZeroScores() {
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            EntitySimilarityScore score = new EntitySimilarityScore(
                "Apple", "Orange",
                "PRODUCT", "PRODUCT",
                0.0, 0.0, 0.0, 0.0, 0.0
            );
            assertEquals(0.0, score.finalScore());
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should accept maximum scores")
    void testCreateInstanceWithMaxScores() {
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            EntitySimilarityScore score = new EntitySimilarityScore(
                "MIT", "MIT",
                "ORGANIZATION", "ORGANIZATION",
                1.0, 1.0, 1.0, 1.0, 1.0
            );
            assertEquals(1.0, score.finalScore());
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for null entity1Name")
    void testThrowExceptionNullEntity1Name() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                null, "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for blank entity1Name")
    void testThrowExceptionBlankEntity1Name() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "  ", "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for null entity2Name")
    void testThrowExceptionNullEntity2Name() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", null,
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for blank entity2Name")
    void testThrowExceptionBlankEntity2Name() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "",
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for null entity1Type")
    void testThrowExceptionNullEntity1Type() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                null, "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for blank entity1Type")
    void testThrowExceptionBlankEntity1Type() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                " ", "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for null entity2Type")
    void testThrowExceptionNullEntity2Type() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", null,
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @Test
    @DisplayName("EntitySimilarityScore should throw exception for blank entity2Type")
    void testThrowExceptionBlankEntity2Type() {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", "",
                0.5, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @ParameterizedTest
    @CsvSource({
        "-0.1, Jaccard",
        "1.1, Jaccard"
    })
    @DisplayName("EntitySimilarityScore should throw exception for invalid jaccardScore")
    void testThrowExceptionInvalidJaccardScore(double invalidScore, String metric) {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                invalidScore, 0.5, 0.5, 0.5, 0.5
            );
        });
    }
    
    @ParameterizedTest
    @CsvSource({
        "-0.1, Containment",
        "1.5, Containment"
    })
    @DisplayName("EntitySimilarityScore should throw exception for invalid containmentScore")
    void testThrowExceptionInvalidContainmentScore(double invalidScore, String metric) {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                0.5, invalidScore, 0.5, 0.5, 0.5
            );
        });
    }
    
    @ParameterizedTest
    @CsvSource({
        "-0.5, Levenshtein",
        "2.0, Levenshtein"
    })
    @DisplayName("EntitySimilarityScore should throw exception for invalid levenshteinScore")
    void testThrowExceptionInvalidLevenshteinScore(double invalidScore, String metric) {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, invalidScore, 0.5, 0.5
            );
        });
    }
    
    @ParameterizedTest
    @CsvSource({
        "-1.0, Abbreviation",
        "1.01, Abbreviation"
    })
    @DisplayName("EntitySimilarityScore should throw exception for invalid abbreviationScore")
    void testThrowExceptionInvalidAbbreviationScore(double invalidScore, String metric) {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, 0.5, invalidScore, 0.5
            );
        });
    }
    
    @ParameterizedTest
    @CsvSource({
        "-0.01, Final",
        "1.001, Final"
    })
    @DisplayName("EntitySimilarityScore should throw exception for invalid finalScore")
    void testThrowExceptionInvalidFinalScore(double invalidScore, String metric) {
        assertThrows(IllegalArgumentException.class, () -> {
            new EntitySimilarityScore(
                "Entity 1", "Entity 2",
                "ORGANIZATION", "ORGANIZATION",
                0.5, 0.5, 0.5, 0.5, invalidScore
            );
        });
    }
    
    // ========================================================================
    // Tests for isDuplicate()
    // ========================================================================
    
    @Test
    @DisplayName("isDuplicate should return true when finalScore equals threshold")
    void testIsDuplicateAtThreshold() {
        // Arrange
        EntitySimilarityScore score = createScore(0.75);
        
        // Act & Assert
        assertTrue(score.isDuplicate(0.75), "Score equal to threshold should be considered duplicate");
    }
    
    @Test
    @DisplayName("isDuplicate should return true when finalScore exceeds threshold")
    void testIsDuplicateAboveThreshold() {
        // Arrange
        EntitySimilarityScore score = createScore(0.85);
        
        // Act & Assert
        assertTrue(score.isDuplicate(0.75), "Score above threshold should be considered duplicate");
    }
    
    @Test
    @DisplayName("isDuplicate should return false when finalScore below threshold")
    void testIsDuplicateBelowThreshold() {
        // Arrange
        EntitySimilarityScore score = createScore(0.65);
        
        // Act & Assert
        assertFalse(score.isDuplicate(0.75), "Score below threshold should not be considered duplicate");
    }
    
    @Test
    @DisplayName("isDuplicate should handle zero threshold")
    void testIsDuplicateZeroThreshold() {
        // Arrange: Any non-zero score
        EntitySimilarityScore score = createScore(0.01);
        
        // Act & Assert
        assertTrue(score.isDuplicate(0.0), "Any score >= 0 should be duplicate with 0 threshold");
    }
    
    @Test
    @DisplayName("isDuplicate should handle threshold of 1.0")
    void testIsDuplicateMaxThreshold() {
        // Arrange: Perfect match
        EntitySimilarityScore perfectScore = createScore(1.0);
        EntitySimilarityScore imperfectScore = createScore(0.99);
        
        // Act & Assert
        assertTrue(perfectScore.isDuplicate(1.0), "Perfect score should match 1.0 threshold");
        assertFalse(imperfectScore.isDuplicate(1.0), "Imperfect score should not match 1.0 threshold");
    }
    
    @ParameterizedTest
    @CsvSource({
        "0.80, 0.75, true",
        "0.75, 0.75, true",
        "0.70, 0.75, false",
        "0.90, 0.80, true",
        "0.50, 0.60, false"
    })
    @DisplayName("isDuplicate should correctly compare finalScore with various thresholds")
    void testIsDuplicateVariousThresholds(double finalScore, double threshold, boolean expected) {
        // Arrange
        EntitySimilarityScore score = createScore(finalScore);
        
        // Act
        boolean result = score.isDuplicate(threshold);
        
        // Assert
        assertEquals(expected, result);
    }
    
    // ========================================================================
    // Tests for hasSameType()
    // ========================================================================
    
    @Test
    @DisplayName("hasSameType should return true for identical types")
    void testHasSameTypeIdentical() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Entity 1", "Entity 2",
            "ORGANIZATION", "ORGANIZATION",
            0.5, 0.5, 0.5, 0.5, 0.5
        );
        
        // Act & Assert
        assertTrue(score.hasSameType());
    }
    
    @Test
    @DisplayName("hasSameType should return true for case-insensitive match")
    void testHasSameTypeCaseInsensitive() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Entity 1", "Entity 2",
            "ORGANIZATION", "organization",
            0.5, 0.5, 0.5, 0.5, 0.5
        );
        
        // Act & Assert
        assertTrue(score.hasSameType(), "Type comparison should be case-insensitive");
    }
    
    @Test
    @DisplayName("hasSameType should return true for mixed case")
    void testHasSameTypeMixedCase() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Entity 1", "Entity 2",
            "Organization", "ORGANIZATION",
            0.5, 0.5, 0.5, 0.5, 0.5
        );
        
        // Act & Assert
        assertTrue(score.hasSameType(), "Type comparison should handle mixed case");
    }
    
    @Test
    @DisplayName("hasSameType should return false for different types")
    void testHasSameTypeDifferent() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Apple", "Apple",
            "ORGANIZATION", "PRODUCT",
            0.5, 0.5, 0.5, 0.5, 0.5
        );
        
        // Act & Assert
        assertFalse(score.hasSameType(), "Different types should return false");
    }
    
    @ParameterizedTest
    @CsvSource({
        "PERSON, PERSON, true",
        "PERSON, person, true",
        "PERSON, Person, true",
        "PERSON, ORGANIZATION, false",
        "PRODUCT, LOCATION, false"
    })
    @DisplayName("hasSameType should handle various type combinations")
    void testHasSameTypeVariousCombinations(String type1, String type2, boolean expected) {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Entity 1", "Entity 2",
            type1, type2,
            0.5, 0.5, 0.5, 0.5, 0.5
        );
        
        // Act
        boolean result = score.hasSameType();
        
        // Assert
        assertEquals(expected, result);
    }
    
    // ========================================================================
    // Tests for toLogString()
    // ========================================================================
    
    @Test
    @DisplayName("toLogString should produce formatted output with all scores")
    void testToLogStringFormat() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "MIT", "Massachusetts Institute of Technology",
            "ORGANIZATION", "ORGANIZATION",
            0.50, 0.75, 0.80, 0.90, 0.73
        );
        
        // Act
        String logString = score.toLogString();
        
        // Assert
        assertNotNull(logString);
        assertFalse(logString.isEmpty());
        
        // Should contain entity names
        assertTrue(logString.contains("MIT"), "Should contain entity1 name");
        assertTrue(logString.contains("Massachusetts Institute of Technology"), "Should contain entity2 name");
        
        // Should contain "Similarity" and bracket markers (locale-independent check)
        assertTrue(logString.contains("Similarity"), "Should contain 'Similarity' keyword");
        assertTrue(logString.contains("["), "Should contain opening bracket");
        assertTrue(logString.contains("]"), "Should contain closing bracket");
        assertTrue(logString.contains("J:"), "Should contain Jaccard marker");
        assertTrue(logString.contains("C:"), "Should contain Containment marker");
        assertTrue(logString.contains("L:"), "Should contain Levenshtein marker");
        assertTrue(logString.contains("A:"), "Should contain Abbreviation marker");
    }
    
    @Test
    @DisplayName("toLogString should handle zero scores")
    void testToLogStringZeroScores() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Apple", "Orange",
            "PRODUCT", "PRODUCT",
            0.0, 0.0, 0.0, 0.0, 0.0
        );
        
        // Act
        String logString = score.toLogString();
        
        // Assert
        assertNotNull(logString);
        assertTrue(logString.contains("Apple"));
        assertTrue(logString.contains("Orange"));
        assertTrue(logString.contains("Similarity"), "Should contain formatted output");
    }
    
    @Test
    @DisplayName("toLogString should handle perfect match scores")
    void testToLogStringPerfectMatch() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "MIT", "MIT",
            "ORGANIZATION", "ORGANIZATION",
            1.0, 1.0, 1.0, 1.0, 1.0
        );
        
        // Act
        String logString = score.toLogString();
        
        // Assert
        assertNotNull(logString);
        assertTrue(logString.contains("MIT"));
        assertTrue(logString.contains("Similarity"), "Should contain formatted output");
    }
    
    @Test
    @DisplayName("toLogString should handle entity names with special characters")
    void testToLogStringSpecialCharacters() {
        // Arrange
        EntitySimilarityScore score = new EntitySimilarityScore(
            "O'Reilly Media", "O'Reilly",
            "ORGANIZATION", "ORGANIZATION",
            0.67, 0.80, 0.85, 0.50, 0.73
        );
        
        // Act & Assert: Should not throw exception
        assertDoesNotThrow(() -> {
            String logString = score.toLogString();
            assertNotNull(logString);
            assertTrue(logString.contains("O'Reilly"));
        });
    }
    
    @Test
    @DisplayName("toLogString should produce consistent format")
    void testToLogStringConsistentFormat() {
        // Arrange
        EntitySimilarityScore score1 = createScore(0.75);
        EntitySimilarityScore score2 = createScore(0.85);
        
        // Act
        String log1 = score1.toLogString();
        String log2 = score2.toLogString();
        
        // Assert: Both should have similar structure
        assertNotNull(log1);
        assertNotNull(log2);
        
        // Both should contain the format markers (e.g., 'J:', 'C:', 'L:', 'A:')
        assertTrue(log1.contains("J:") || log1.split(":").length >= 2);
        assertTrue(log2.contains("J:") || log2.split(":").length >= 2);
    }
    
    // ========================================================================
    // Integration Tests
    // ========================================================================
    
    @Test
    @DisplayName("Integration: Warren Home example from research.md")
    void testIntegrationWarrenHomeExample() {
        // Arrange: Warren State Home vs Warren Home (67% Jaccard from research.md)
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Warren State Home", "Warren Home",
            "ORGANIZATION", "ORGANIZATION",
            0.667, 0.50, 0.75, 0.20, 0.60
        );
        
        // Act & Assert
        assertTrue(score.hasSameType(), "Should have same type");
        assertFalse(score.isDuplicate(0.75), "Should not be duplicate with high threshold");
        assertTrue(score.isDuplicate(0.50), "Should be duplicate with lower threshold");
        
        String logString = score.toLogString();
        assertNotNull(logString);
        assertTrue(logString.contains("Warren"));
    }
    
    @Test
    @DisplayName("Integration: MIT abbreviation example")
    void testIntegrationMITExample() {
        // Arrange: MIT vs Massachusetts Institute of Technology
        EntitySimilarityScore score = new EntitySimilarityScore(
            "MIT", "Massachusetts Institute of Technology",
            "ORGANIZATION", "ORGANIZATION",
            0.00, 0.10, 0.15, 1.00, 0.35  // High abbreviation score
        );
        
        // Act & Assert
        assertTrue(score.hasSameType());
        assertEquals(1.0, score.abbreviationScore(), "Should have perfect abbreviation match");
        assertFalse(score.isDuplicate(0.75), "Abbreviation alone may not exceed threshold");
    }
    
    @Test
    @DisplayName("Integration: Apple type safety example")
    void testIntegrationAppleTypeSafetyExample() {
        // Arrange: Apple organization vs Apple product
        EntitySimilarityScore score = new EntitySimilarityScore(
            "Apple", "Apple",
            "ORGANIZATION", "PRODUCT",
            1.0, 1.0, 1.0, 1.0, 0.0  // Should be 0.0 due to type mismatch
        );
        
        // Act & Assert
        assertFalse(score.hasSameType(), "Should have different types");
        assertEquals(0.0, score.finalScore(), "Final score should be 0.0 for type mismatch");
        // Note: 0.0 >= 0.0 is true in Java, so isDuplicate(0.0) returns true
        // This test just verifies the finalScore is 0.0
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Helper method to create a score with specific final score value.
     */
    private EntitySimilarityScore createScore(double finalScore) {
        return new EntitySimilarityScore(
            "Entity A", "Entity B",
            "ORGANIZATION", "ORGANIZATION",
            0.5, 0.5, 0.5, 0.5, finalScore
        );
    }
}
