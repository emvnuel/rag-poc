package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Calculator for entity similarity using multiple metrics.
 * 
 * Combines four complementary similarity metrics:
 * - Jaccard similarity (token overlap)
 * - Containment (substring matching)
 * - Levenshtein distance (edit distance)
 * - Abbreviation matching
 */
@ApplicationScoped
public class EntitySimilarityCalculator {
    
    private static final Logger logger = LoggerFactory.getLogger(EntitySimilarityCalculator.class);
    
    @Inject
    DeduplicationConfig config;
    
    /**
     * Computes similarity between two entities.
     * 
     * Returns a detailed score breakdown with individual metric scores
     * and a weighted final score.
     * 
     * Includes early termination heuristics for performance optimization.
     * 
     * @param entity1 First entity (must not be null)
     * @param entity2 Second entity (must not be null)
     * @return EntitySimilarityScore with metric breakdown
     * @throws IllegalArgumentException if either entity is null
     */
    public EntitySimilarityScore computeSimilarity(Entity entity1, Entity entity2) {
        if (entity1 == null) {
            throw new IllegalArgumentException("entity1 cannot be null");
        }
        if (entity2 == null) {
            throw new IllegalArgumentException("entity2 cannot be null");
        }
        
        String name1 = entity1.getEntityName();
        String name2 = entity2.getEntityName();
        String type1 = entity1.getEntityType() != null ? entity1.getEntityType() : "";
        String type2 = entity2.getEntityType() != null ? entity2.getEntityType() : "";
        
        // Type safety check
        if (!type1.equalsIgnoreCase(type2)) {
            return new EntitySimilarityScore(
                name1, name2, type1, type2,
                0.0, 0.0, 0.0, 0.0, 0.0
            );
        }
        
        // Early termination heuristic 1: Length ratio check
        // If names differ drastically in length (more than 5x), they're unlikely to match
        // This is conservative to avoid rejecting "Warren Home" vs "Warren State Home and Training School"
        int len1 = name1.length();
        int len2 = name2.length();
        int maxLen = Math.max(len1, len2);
        int minLen = Math.min(len1, len2);
        
        if (minLen > 10 && maxLen > minLen * 5) {
            // Names differ too much in length, unlikely to be similar
            return new EntitySimilarityScore(
                name1, name2, type1, type2,
                0.0, 0.0, 0.0, 0.0, 0.0
            );
        }
        
        // Early termination heuristic 2: First token check
        // If first tokens are completely different (no shared prefix), entities are likely not similar
        // Exception: potential abbreviations (one token is very short)
        String[] tokens1 = name1.toLowerCase().split("\\s+");
        String[] tokens2 = name2.toLowerCase().split("\\s+");
        
        if (tokens1.length > 0 && tokens2.length > 0) {
            String firstToken1 = tokens1[0];
            String firstToken2 = tokens2[0];
            
            // Skip first token check if either name might be an abbreviation
            // (short name with no spaces, like "MIT")
            boolean isPotentialAbbreviation = 
                (name1.length() <= 10 && !name1.contains(" ")) || 
                (name2.length() <= 10 && !name2.contains(" "));
            
            if (!isPotentialAbbreviation) {
                // Check if first tokens share at least first 2 characters OR have significant character overlap
                boolean hasSharedPrefix = false;
                if (firstToken1.length() >= 2 && firstToken2.length() >= 2) {
                    hasSharedPrefix = firstToken1.substring(0, 2).equals(firstToken2.substring(0, 2));
                }
                
                // Count shared characters
                int sharedChars = 0;
                for (char c : firstToken1.toCharArray()) {
                    if (firstToken2.indexOf(c) >= 0) {
                        sharedChars++;
                    }
                }
                
                // If first tokens share prefix or have >50% character overlap, continue
                // Otherwise skip expensive computation
                boolean hasOverlap = hasSharedPrefix || (sharedChars > firstToken1.length() / 2);
                
                if (!hasOverlap) {
                    return new EntitySimilarityScore(
                        name1, name2, type1, type2,
                        0.0, 0.0, 0.0, 0.0, 0.0
                    );
                }
            }
        }
        
        // Compute individual metrics
        double jaccard = computeJaccardSimilarity(name1, name2);
        double containment = computeContainmentScore(name1, name2);
        double levenshtein = computeLevenshteinSimilarity(name1, name2);
        double abbreviation = computeAbbreviationScore(name1, name2);
        
        // Weighted final score using config weights
        double finalScore = config.weight().jaccard() * jaccard 
                          + config.weight().containment() * containment 
                          + config.weight().edit() * levenshtein 
                          + config.weight().abbreviation() * abbreviation;
        
        // Log similarity computation for debugging
        if (abbreviation > 0.5 || finalScore > 0.3) {
            logger.debug("Similarity '{}' vs '{}': jaccard={}, containment={}, edit={}, abbr={}, final={}",
                name1, name2, jaccard, containment, levenshtein, abbreviation, finalScore);
        }
        
        return new EntitySimilarityScore(
            name1, name2, type1, type2,
            jaccard, containment, levenshtein, abbreviation, finalScore
        );
    }
    
    /**
     * Computes similarity between two entity names with type constraint.
     * 
     * Returns 0.0 if entity types don't match (type-aware matching).
     * 
     * @param name1 First entity name (must not be null or blank)
     * @param name2 Second entity name (must not be null or blank)
     * @param type1 First entity type (must not be null)
     * @param type2 Second entity type (must not be null)
     * @return Similarity score [0.0, 1.0], or 0.0 if types don't match
     * @throws IllegalArgumentException if any parameter is null or blank
     */
    public double computeNameSimilarity(String name1, String name2, String type1, String type2) {
        if (name1 == null || name1.isBlank()) {
            throw new IllegalArgumentException("name1 cannot be null or blank");
        }
        if (name2 == null || name2.isBlank()) {
            throw new IllegalArgumentException("name2 cannot be null or blank");
        }
        if (type1 == null) {
            throw new IllegalArgumentException("type1 cannot be null");
        }
        if (type2 == null) {
            throw new IllegalArgumentException("type2 cannot be null");
        }
        
        // Type safety: never merge entities of different types
        if (!type1.equalsIgnoreCase(type2)) {
            return 0.0;
        }
        
        // Compute individual metrics
        double jaccard = computeJaccardSimilarity(name1, name2);
        double containment = computeContainmentScore(name1, name2);
        double levenshtein = computeLevenshteinSimilarity(name1, name2);
        double abbreviation = computeAbbreviationScore(name1, name2);
        
        // Weighted combination using configured weights
        return config.weight().jaccard() * jaccard 
             + config.weight().containment() * containment 
             + config.weight().edit() * levenshtein 
             + config.weight().abbreviation() * abbreviation;
    }
    
    /**
     * Computes Jaccard similarity (token overlap) between two entity names.
     * 
     * Formula: |intersection| / |union| of token sets
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Jaccard score [0.0, 1.0]
     */
    public double computeJaccardSimilarity(String name1, String name2) {
        if (name1 == null) {
            throw new IllegalArgumentException("name1 cannot be null");
        }
        if (name2 == null) {
            throw new IllegalArgumentException("name2 cannot be null");
        }
        
        Set<String> tokens1 = tokenize(name1);
        Set<String> tokens2 = tokenize(name2);
        
        // Empty strings should return 0.0
        if (tokens1.isEmpty() && tokens2.isEmpty()) {
            return 0.0;
        }
        if (tokens1.isEmpty() || tokens2.isEmpty()) {
            return 0.0;
        }
        
        // Compute intersection
        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);
        
        // Compute union
        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);
        
        return (double) intersection.size() / union.size();
    }
    
    /**
     * Computes containment score (substring matching).
     * 
     * Returns 1.0 if one name contains the other (after normalization),
     * 0.0 otherwise.
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Containment score (0.0 or 1.0)
     */
    public double computeContainmentScore(String name1, String name2) {
        if (name1 == null) {
            throw new IllegalArgumentException("name1 cannot be null");
        }
        if (name2 == null) {
            throw new IllegalArgumentException("name2 cannot be null");
        }
        
        String normalized1 = normalizeName(name1);
        String normalized2 = normalizeName(name2);
        
        if (normalized1.contains(normalized2) || normalized2.contains(normalized1)) {
            return 1.0;
        }
        
        return 0.0;
    }
    
    /**
     * Computes normalized Levenshtein similarity (edit distance).
     * 
     * Formula: 1 - (editDistance / maxLength)
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Levenshtein similarity [0.0, 1.0]
     */
    public double computeLevenshteinSimilarity(String name1, String name2) {
        if (name1 == null) {
            throw new IllegalArgumentException("name1 cannot be null");
        }
        if (name2 == null) {
            throw new IllegalArgumentException("name2 cannot be null");
        }
        
        String normalized1 = normalizeName(name1);
        String normalized2 = normalizeName(name2);
        
        if (normalized1.equals(normalized2)) {
            return 1.0;
        }
        
        int maxLength = Math.max(normalized1.length(), normalized2.length());
        if (maxLength == 0) {
            return 1.0;
        }
        
        int distance = computeLevenshteinDistance(normalized1, normalized2);
        return 1.0 - ((double) distance / maxLength);
    }
    
    /**
     * Computes the Levenshtein distance between two strings.
     * 
     * Includes early termination when the current minimum exceeds a threshold.
     * 
     * @param s1 First string
     * @param s2 Second string
     * @return Edit distance
     */
    private int computeLevenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        
        if (len1 == 0) return len2;
        if (len2 == 0) return len1;
        
        // Note: We don't do early termination based on length difference here
        // because abbreviations like "MIT" vs "Massachusetts Institute of Technology"
        // have large length differences but should still be compared
        int maxLength = Math.max(len1, len2);
        
        int[][] dp = new int[len1 + 1][len2 + 1];
        
        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[len1][len2];
    }
    
    /**
     * Computes abbreviation matching score.
     * 
     * Returns 1.0 if one name is an acronym of the other (e.g., "MIT" vs
     * "Massachusetts Institute of Technology"), 0.0 otherwise.
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Abbreviation score (0.0 or 1.0)
     */
    public double computeAbbreviationScore(String name1, String name2) {
        if (name1 == null) {
            throw new IllegalArgumentException("name1 cannot be null");
        }
        if (name2 == null) {
            throw new IllegalArgumentException("name2 cannot be null");
        }
        
        String normalized1 = normalizeName(name1);
        String normalized2 = normalizeName(name2);
        
        // Identical names get full score
        if (normalized1.equals(normalized2)) {
            return 1.0;
        }
        
        // Check if either could be an acronym (short, no spaces)
        if (isAcronymMatch(normalized1, normalized2)) {
            return 1.0;
        }
        if (isAcronymMatch(normalized2, normalized1)) {
            return 1.0;
        }
        
        return 0.0;
    }
    
    /**
     * Checks if the short name is an acronym of the long name.
     * 
     * @param shortName Potential acronym
     * @param longName Full name
     * @return true if shortName matches first letters of longName words (skipping common stop words)
     */
    private boolean isAcronymMatch(String shortName, String longName) {
        // Short name should have no spaces and be shorter
        if (shortName.contains(" ") || shortName.length() >= longName.length()) {
            return false;
        }
        
        String[] words = longName.split("\\s+");
        if (words.length < 2) {
            return false;
        }
        
        // Common stop words to skip in acronyms
        Set<String> stopWords = Set.of("a", "an", "the", "of", "and", "or", "for", "in", "on", "at", "to", "from");
        
        // Extract first letters, skipping stop words
        StringBuilder acronym = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty() && !stopWords.contains(word)) {
                acronym.append(word.charAt(0));
            }
        }
        
        return shortName.equals(acronym.toString());
    }
    
    /**
     * Normalizes an entity name for comparison.
     * 
     * Normalization steps:
     * - Convert to lowercase
     * - Trim whitespace
     * - Collapse multiple spaces to single space
     * - Remove punctuation
     * 
     * @param name Entity name to normalize (must not be null)
     * @return Normalized entity name
     */
    public String normalizeName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        
        // Remove all punctuation, lowercase, trim, and collapse spaces
        return name.replaceAll("[^a-zA-Z0-9\\s]", "")
                   .toLowerCase()
                   .trim()
                   .replaceAll("\\s+", " ");
    }
    
    /**
     * Tokenizes an entity name into words.
     * 
     * @param name Entity name to tokenize (must not be null)
     * @return Set of tokens (words)
     */
    public Set<String> tokenize(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        
        String normalized = normalizeName(name);
        if (normalized.isEmpty()) {
            return new HashSet<>();
        }
        
        String[] tokens = normalized.split("\\s+");
        Set<String> result = new HashSet<>();
        for (String token : tokens) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }
}
