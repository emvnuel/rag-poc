package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Prompts for extracting code-specific entities and relationships.
 * Provides specialized prompts for analyzing source code rather than general
 * text.
 * 
 * <p>
 * Prompts are configurable via application.properties and .env files:
 * <ul>
 * <li>lightrag.code.extraction.system.prompt - System prompt template</li>
 * <li>lightrag.code.extraction.user.prompt - User prompt template</li>
 * </ul>
 * 
 * <p>
 * Default prompts are comprehensive and optimized for code analysis.
 * Override via environment variables for domain-specific customization.
 */
@ApplicationScoped
public class CodeExtractionPrompts {

  /**
   * Default entity types for code extraction.
   * Focuses on code-specific concepts rather than general entities.
   */
  public static final String DEFAULT_CODE_ENTITY_TYPES = "function,class,module,interface,variable,api_endpoint,dependency,type,method,constant,enum,struct,trait";

  /**
   * Default relationship types for code extraction.
   * Focuses on code-specific relationships like calls, imports, inheritance.
   */
  public static final String DEFAULT_CODE_RELATIONSHIP_TYPES = "calls,imports,inherits,implements,depends_on,uses,defined_in,exports,references,throws,returns";

  @ConfigProperty(name = "lightrag.code.extraction.system.prompt")
  String systemPrompt;

  @ConfigProperty(name = "lightrag.code.extraction.user.prompt")
  String userPrompt;

  /**
   * Formats the code entity extraction system prompt with specific entity types
   * and language.
   * Uses configured prompt from application.properties.
   * 
   * @param entityTypes       Comma-separated list of entity types (or null for
   *                          default)
   * @param relationshipTypes Comma-separated list of relationship types (or null
   *                          for default)
   * @param language          Programming language being analyzed (or "unknown")
   * @return Formatted system prompt
   */
  public String formatSystemPrompt(final String entityTypes,
      final String relationshipTypes,
      final String language) {
    final String types = entityTypes != null ? entityTypes : DEFAULT_CODE_ENTITY_TYPES;
    final String relTypes = relationshipTypes != null ? relationshipTypes : DEFAULT_CODE_RELATIONSHIP_TYPES;
    final String lang = language != null ? language : "unknown";

    return systemPrompt
        .replace("{entity_types}", types)
        .replace("{relationship_types}", relTypes)
        .replace("{language}", lang);
  }

  /**
   * Formats the user prompt with the actual source code.
   * Uses configured prompt from application.properties.
   * 
   * @param sourceCode The source code to analyze
   * @return Formatted user prompt
   */
  public String formatUserPrompt(final String sourceCode) {
    return userPrompt.replace("{input_text}", sourceCode != null ? sourceCode : "");
  }
}
