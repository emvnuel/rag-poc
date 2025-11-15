package br.edu.ifba.lightrag.core;

/**
 * Represents the similarity score between two entities.
 * 
 * The score is a weighted combination of multiple metrics:
 * - Jaccard similarity (token overlap)
 * - Containment (substring matching)
 * - Levenshtein distance (edit distance)
 * - Abbreviation matching
 * 
 * @param entity1Name Name of the first entity
 * @param entity2Name Name of the second entity
 * @param entity1Type Type of the first entity (PERSON, ORGANIZATION, etc.)
 * @param entity2Type Type of the second entity
 * @param jaccardScore Jaccard similarity score [0.0, 1.0]
 * @param containmentScore Containment score [0.0, 1.0] (1.0 if one name contains the other)
 * @param levenshteinScore Normalized Levenshtein similarity [0.0, 1.0]
 * @param abbreviationScore Abbreviation match score [0.0, 1.0]
 * @param finalScore Weighted combined score [0.0, 1.0]
 */
public record EntitySimilarityScore(
    String entity1Name,
    String entity2Name,
    String entity1Type,
    String entity2Type,
    double jaccardScore,
    double containmentScore,
    double levenshteinScore,
    double abbreviationScore,
    double finalScore
) {
    
    /**
     * Creates an EntitySimilarityScore with validation.
     */
    public EntitySimilarityScore {
        if (entity1Name == null || entity1Name.isBlank()) {
            throw new IllegalArgumentException("entity1Name cannot be null or blank");
        }
        if (entity2Name == null || entity2Name.isBlank()) {
            throw new IllegalArgumentException("entity2Name cannot be null or blank");
        }
        if (entity1Type == null || entity1Type.isBlank()) {
            throw new IllegalArgumentException("entity1Type cannot be null or blank");
        }
        if (entity2Type == null || entity2Type.isBlank()) {
            throw new IllegalArgumentException("entity2Type cannot be null or blank");
        }
        if (jaccardScore < 0.0 || jaccardScore > 1.0) {
            throw new IllegalArgumentException("jaccardScore must be in [0.0, 1.0]");
        }
        if (containmentScore < 0.0 || containmentScore > 1.0) {
            throw new IllegalArgumentException("containmentScore must be in [0.0, 1.0]");
        }
        if (levenshteinScore < 0.0 || levenshteinScore > 1.0) {
            throw new IllegalArgumentException("levenshteinScore must be in [0.0, 1.0]");
        }
        if (abbreviationScore < 0.0 || abbreviationScore > 1.0) {
            throw new IllegalArgumentException("abbreviationScore must be in [0.0, 1.0]");
        }
        if (finalScore < 0.0 || finalScore > 1.0) {
            throw new IllegalArgumentException("finalScore must be in [0.0, 1.0]");
        }
    }
    
    /**
     * Returns true if the entities should be considered duplicates based on the threshold.
     * 
     * @param threshold The similarity threshold [0.0, 1.0]
     * @return true if finalScore >= threshold
     */
    public boolean isDuplicate(double threshold) {
        return finalScore >= threshold;
    }
    
    /**
     * Returns true if the entities have the same type.
     * 
     * @return true if entity types match (case-insensitive)
     */
    public boolean hasSameType() {
        return entity1Type.equalsIgnoreCase(entity2Type);
    }
    
    /**
     * Returns a formatted string representation for logging.
     * 
     * @return Formatted string with similarity scores
     */
    public String toLogString() {
        return String.format(
            "Similarity('%s' vs '%s'): %.3f [J:%.2f C:%.2f L:%.2f A:%.2f]",
            entity1Name, entity2Name, finalScore,
            jaccardScore, containmentScore, levenshteinScore, abbreviationScore
        );
    }
}
