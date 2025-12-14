package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Prompts for extracting code-specific entities and relationships.
 * Provides specialized prompts for analyzing source code rather than general text.
 * 
 * <p>Prompts are configurable via application.properties and .env files:
 * <ul>
 *   <li>lightrag.code.extraction.system.prompt - System prompt template</li>
 *   <li>lightrag.code.extraction.user.prompt - User prompt template</li>
 * </ul>
 * 
 * <p>Default prompts are comprehensive and optimized for code analysis.
 * Override via environment variables for domain-specific customization.
 */
@ApplicationScoped
public class CodeExtractionPrompts {
    
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
     * Default system prompt for code entity extraction.
     * Comprehensive prompt with guidelines for extracting code entities.
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
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
        8. **Language awareness**: Adapt extraction to language-specific constructs (Language: {language})
        
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
        
        Now, analyze the following source code and extract all relevant entities and relationships.
        """;
    
    /**
     * Default user prompt for code extraction.
     */
    private static final String DEFAULT_USER_PROMPT = 
        "Source code to analyze:\n\n{input_text}\n\n" +
        "Please extract all entities and relationships following the guidelines above.";
    
    @ConfigProperty(name = "lightrag.code.extraction.system.prompt", defaultValue = "")
    String configuredSystemPrompt;
    
    @ConfigProperty(name = "lightrag.code.extraction.user.prompt", defaultValue = "")
    String configuredUserPrompt;
    
    /**
     * Formats the code entity extraction system prompt with specific entity types and language.
     * Uses configured prompt from application.properties, or falls back to comprehensive default.
     * 
     * @param entityTypes Comma-separated list of entity types (or null for default)
     * @param relationshipTypes Comma-separated list of relationship types (or null for default)
     * @param language Programming language being analyzed (or "unknown")
     * @return Formatted system prompt
     */
    public String formatSystemPrompt(final String entityTypes, 
                                    final String relationshipTypes,
                                    final String language) {
        final String types = entityTypes != null ? entityTypes : DEFAULT_CODE_ENTITY_TYPES;
        final String relTypes = relationshipTypes != null ? relationshipTypes : DEFAULT_CODE_RELATIONSHIP_TYPES;
        final String lang = language != null ? language : "unknown";
        
        // Use configured prompt if available, otherwise use default
        final String basePrompt = configuredSystemPrompt != null && !configuredSystemPrompt.isEmpty()
            ? configuredSystemPrompt
            : DEFAULT_SYSTEM_PROMPT;
        
        return basePrompt
            .replace("{entity_types}", types)
            .replace("{relationship_types}", relTypes)
            .replace("{language}", lang);
    }
    
    /**
     * Formats the user prompt with the actual source code.
     * Uses configured prompt from application.properties, or falls back to default.
     * 
     * @param sourceCode The source code to analyze
     * @return Formatted user prompt
     */
    public String formatUserPrompt(final String sourceCode) {
        // Use configured prompt if available, otherwise use default
        final String basePrompt = configuredUserPrompt != null && !configuredUserPrompt.isEmpty()
            ? configuredUserPrompt
            : DEFAULT_USER_PROMPT;
        
        return basePrompt.replace("{input_text}", sourceCode != null ? sourceCode : "");
    }
}
