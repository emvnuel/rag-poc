# Agent Guidelines for rag-saas

## Build/Test/Run Commands
- **Build**: `./mvnw package` or `./mvnw clean package`
- **Test all**: `./mvnw test`
- **Test single**: `./mvnw test -Dtest=ExampleResourceTest` or `./mvnw test -Dtest=ExampleResourceTest#testHelloEndpoint`
- **Integration tests**: `./mvnw verify -DskipITs=false`

## Project Info
- **Framework**: Quarkus 3.28.4 with Jakarta REST (not JAX-RS/RESTEasy)
- **Java version**: 21
- **Package**: `br.edu.ifba`

## Code Style
- **Imports**: Jakarta (`jakarta.ws.rs.*`), not javax. Standard imports before third-party. NEVER use inline imports (e.g., avoid `import br.edu.ifba.lightrag.core.EntityResolver; import br.edu.ifba.lightrag.core.DeduplicationConfig;` on single lines). Always use separate lines for each import.
- **Annotations**: Use `@Path`, `@GET/@POST`, `@Produces`, `@QuarkusTest` for tests
- **Testing**: JUnit 5 + REST Assured. Pattern: `given().when().get("/path").then().statusCode(200).body(is("expected"))`
- **Naming**: Classes end with `Resource` for REST endpoints, `Test` for tests, `IT` for integration tests
- **Error handling**: Use `IllegalArgumentException` for validation, null checks before operations
- **Javadoc**: Include for public methods with `@param` and `@return` tags where helpful
- **Finals**: Use `final` for utility classes and immutable variables
