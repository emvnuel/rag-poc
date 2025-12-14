package br.edu.ifba.lightrag.core;

/**
 * Prompts for extracting code-specific entities and relationships.
 * Provides specialized prompts for analyzing source code rather than general text.
 */
public final class CodeExtractionPrompts {
    
    private CodeExtractionPrompts() {
        // Utility class
    }
    
    /**
     * Default entity types for code extraction.
     * Focuses on code-specific concepts rather than general entities.
     */
    public static final String DEFAULT_CODE_ENTITY_TYPES = 
        "function,class,module,interface,variable,api_endpoint,dependency,type,method,constant,enum,struct,trait";
    
    /**
     * Default relationship types for code extraction.
     * Focuses on code-specific relationships like calls, imports, inheritance.
     */
    public static final String DEFAULT_CODE_RELATIONSHIP_TYPES = 
        "calls,imports,inherits,implements,depends_on,uses,defined_in,exports,references,throws,returns";
    
    /**
     * System prompt for extracting entities and relationships from source code.
     * Instructs the LLM to focus on code structure rather than natural language patterns.
     * 
     * <p>Template variables:
     * <ul>
     *   <li>{entity_types} - Comma-separated list of entity types to extract</li>
     *   <li>{language} - Programming language being analyzed (if known)</li>
     *   <li>{input_text} - The source code to analyze</li>
     * </ul>
     */
    public static final String CODE_ENTITY_EXTRACTION_SYSTEM_PROMPT = """
        You are an expert code analyzer. Your task is to extract entities and relationships from source code.
        
        # Entity Types
        Focus on these code-specific entity types: {entity_types}
        
        # Entity Guidelines
        - **function/method**: Extract function and method names with their signatures (parameters, return type)
        - **class**: Extract class names, including nested classes and inner classes
        - **module**: Extract module/package names and namespaces
        - **interface**: Extract interface names and trait definitions
        - **type**: Extract type definitions (typedefs, type aliases, generic types)
        - **variable**: Extract significant variables (constants, global variables, class fields)
        - **api_endpoint**: Extract REST endpoints, GraphQL resolvers, RPC methods
        - **dependency**: Extract external dependencies (imported libraries, packages)
        - **enum**: Extract enumeration types and their values
        - **struct**: Extract struct/record definitions
        
        # Relationship Types
        Extract these code-specific relationships: {relationship_types}
        
        # Relationship Guidelines
        - **calls**: Function/method call relationships (A calls B)
        - **imports**: Import/require/include relationships (A imports B)
        - **inherits**: Class inheritance (A inherits B, A extends B)
        - **implements**: Interface implementation (A implements B)
        - **depends_on**: Dependency relationships (A depends on B)
        - **uses**: Usage relationships (A uses B)
        - **defined_in**: Location relationships (function A defined in class B)
        - **exports**: Export relationships (module A exports function B)
        - **references**: Reference relationships (A references variable B)
        - **throws**: Exception relationships (function A throws exception B)
        - **returns**: Return type relationships (function A returns type B)
        
        # Extraction Rules
        1. **Be precise**: Extract exact names as they appear in code (case-sensitive)
        2. **Include signatures**: For functions, include parameter types if clearly visible
        3. **Preserve scope**: Note whether entities are public, private, protected, static
        4. **Track imports**: Carefully extract all import/require/include statements
        5. **Identify patterns**: Look for common patterns like factory functions, singletons, decorators
        6. **Note comments**: Include meaningful docstring/comment content as entity descriptions
        7. **Ignore noise**: Skip trivial variables (loop counters, temporary variables)
        8. **Language awareness**: Adapt extraction to language-specific constructs (e.g., Python decorators, Java annotations, Rust traits)
        
        # Output Format
        Return a JSON object with two lists:
        ```json
        {
          "entities": [
            {
              "name": "exact_entity_name",
              "type": "one_of_entity_types",
              "description": "Brief description including signature, purpose, and any relevant documentation"
            }
          ],
          "relationships": [
            {
              "source": "entity_name_1",
              "target": "entity_name_2",
              "type": "one_of_relationship_types",
              "description": "Brief description of the relationship context"
            }
          ]
        }
        ```
        
        # Example (Java):
        Input:
        ```java
        public class UserService {
            private UserRepository repository;
            
            public User findById(Long id) {
                return repository.findById(id);
            }
        }
        ```
        
        Output:
        ```json
        {
          "entities": [
            {"name": "UserService", "type": "class", "description": "Service class for user operations"},
            {"name": "findById", "type": "method", "description": "Finds a user by ID, signature: public User findById(Long id)"},
            {"name": "UserRepository", "type": "dependency", "description": "Repository dependency for user data access"},
            {"name": "User", "type": "type", "description": "Return type representing a user entity"}
          ],
          "relationships": [
            {"source": "UserService", "target": "UserRepository", "type": "depends_on", "description": "UserService depends on UserRepository for data access"},
            {"source": "findById", "target": "UserService", "type": "defined_in", "description": "findById method is defined in UserService class"},
            {"source": "findById", "target": "repository", "type": "calls", "description": "findById calls repository.findById method"},
            {"source": "findById", "target": "User", "type": "returns", "description": "findById returns User type"}
          ]
        }
        ```
        
        Now, analyze the following source code and extract all relevant entities and relationships.
        """;
    
    /**
     * User prompt for code extraction (appended after system prompt).
     */
    public static final String CODE_ENTITY_EXTRACTION_USER_PROMPT = 
        "Source code to analyze:\n\n{input_text}\n\n" +
        "Please extract all entities and relationships following the guidelines above.";
    
    /**
     * Formats the code entity extraction system prompt with specific entity types and language.
     * 
     * @param entityTypes Comma-separated list of entity types (or null for default)
     * @param relationshipTypes Comma-separated list of relationship types (or null for default)
     * @param language Programming language being analyzed (or "unknown")
     * @return Formatted system prompt
     */
    public static String formatSystemPrompt(final String entityTypes, 
                                           final String relationshipTypes,
                                           final String language) {
        final String types = entityTypes != null ? entityTypes : DEFAULT_CODE_ENTITY_TYPES;
        final String relTypes = relationshipTypes != null ? relationshipTypes : DEFAULT_CODE_RELATIONSHIP_TYPES;
        final String lang = language != null ? language : "unknown";
        
        return CODE_ENTITY_EXTRACTION_SYSTEM_PROMPT
            .replace("{entity_types}", types)
            .replace("{relationship_types}", relTypes)
            .replace("{language}", lang);
    }
    
    /**
     * Formats the user prompt with the actual source code.
     * 
     * @param sourceCode The source code to analyze
     * @return Formatted user prompt
     */
    public static String formatUserPrompt(final String sourceCode) {
        return CODE_ENTITY_EXTRACTION_USER_PROMPT
            .replace("{input_text}", sourceCode != null ? sourceCode : "");
    }
}
