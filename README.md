# RAG-SaaS - Multi-Tenant Knowledge Graph Platform

A production-ready Retrieval-Augmented Generation (RAG) platform built with Quarkus, featuring **project-level graph isolation** for secure multi-tenant knowledge management.

## Key Features

### Project-Level Graph Isolation (Spec 001)
Each project gets its own isolated knowledge graph with **zero data leakage** between tenants:

- **Per-project Apache AGE graphs**: Each project ID maps to `graph_<uuid_prefix>` for complete isolation
- **Automatic lifecycle management**: Graphs are created with projects and deleted atomically (no orphans)
- **High performance**: P95 query latency <300ms, project deletion <60s (10K entities)
- **Concurrent-safe**: 50+ projects can upload/query simultaneously without contention

**Use Cases**:
- Multi-tenant SaaS where each customer needs isolated knowledge
- Separate RAG contexts per department/team/product line
- Development/staging/production environment isolation

See [Graph Isolation Quickstart](./specs/001-graph-isolation/quickstart.md) for testing guide.

### Hybrid RAG Architecture
- **5 Query Modes**: NAIVE (vector-only), LOCAL (entity+graph), GLOBAL (community), HYBRID (vector+graph), MIX (combined)
- **Apache AGE Integration**: Cypher-like graph queries with PostgreSQL ACID guarantees
- **PGVector Storage**: Efficient similarity search with halfvec compression
- **LightRAG Core**: Entity extraction, relationship mapping, chunk management

## Technology Stack

- **Framework**: Quarkus 3.28.4 (Java 21)
- **Database**: PostgreSQL 15+ with Apache AGE 1.5.0 + pgvector
- **Graph**: Apache AGE (graph database extension)
- **Vectors**: pgvector with halfvec-4096 compression
- **ORM**: Hibernate ORM with Panache
- **REST**: Jakarta REST (not JAX-RS)

## Quick Start

### Prerequisites
- Docker and Docker Compose
- Java 21+
- Maven 3.9+

### Run Locally

```bash
# Start PostgreSQL with AGE + pgvector
docker-compose up -d postgres

# Build and run in dev mode
./mvnw quarkus:dev
```

Application available at: `http://localhost:8080`

### Create Your First Isolated Project

```bash
# Create a project
PROJECT_ID=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "My Research Project"}' | jq -r '.id')

# Upload a document
curl -X POST http://localhost:8080/api/projects/$PROJECT_ID/documents \
  -F "file=@research-paper.pdf" \
  -F "type=PDF"

# Query with graph-enhanced RAG
curl -X POST http://localhost:8080/api/projects/$PROJECT_ID/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "Summarize key findings", "mode": "HYBRID"}' | jq '.response'
```

Each project gets its own isolated graph - entities from "My Research Project" won't leak into other projects.

## Architecture

### Graph Isolation Model
```
Project A (UUID: 019a-8612-045d-...) → Graph: graph_019a8612045d...
  ├── Entities: Apple, OpenAI, Microsoft
  ├── Relations: Apple→OpenAI (partnership)
  └── Vectors: 1,234 embeddings

Project B (UUID: 019b-7423-136e-...) → Graph: graph_019b7423136e...
  ├── Entities: Apple, Tesla, Google  
  ├── Relations: Apple→Tesla (supplier)
  └── Vectors: 2,456 embeddings
```

**Key Properties**:
- Entity "Apple" exists independently in both graphs (no shared state)
- Queries are automatically scoped by `projectId` parameter
- Deletion of Project A removes its graph, entities, vectors atomically
- No cross-project queries possible (enforced at GraphStorage layer)

### Performance Characteristics

Based on implementation testing (see [research.md](./specs/001-graph-isolation/research.md)):

| Operation | P95 Latency | Notes |
|-----------|-------------|-------|
| Graph creation | <100ms | Async, non-blocking |
| Entity upsert (single) | <20ms | Cypher MERGE operation |
| Entity batch upsert (100) | <200ms | Batched for efficiency |
| 2-hop graph traversal | <300ms | MATCH (a)-[:RELATED_TO*1..2]-(b) |
| Project deletion (10K entities) | <60s | Cascading delete with DROP SCHEMA |
| Concurrent 50 projects | No contention | Independent graph namespaces |

### Migration from Shared Graph

If you have an existing deployment with a single shared graph, see [Migration Guide](./specs/001-graph-isolation/plan.md#migration-strategy):

1. **Preparation**: Backup database, stop writes
2. **Partition**: Run migration script to split graphs by project_id
3. **Validation**: Verify entity counts match pre-migration
4. **Cutover**: Deploy new version with per-project routing
5. **Rollback**: Merge graphs back if issues (within 24h window)

**Estimated downtime**: 4 hours for 100K entities across 20 projects.

## Project Structure

```
rag-saas/
├── src/main/java/br/edu/ifba/
│   ├── lightrag/
│   │   ├── storage/impl/AgeGraphStorage.java  # Graph isolation implementation
│   │   ├── core/LightRAG.java                 # RAG orchestration
│   │   └── query/*.java                       # Query executors (5 modes)
│   ├── project/ProjectService.java            # Project lifecycle + graph creation
│   ├── document/DocumentService.java          # Document processing
│   └── chat/ChatService.java                  # Query endpoint
├── specs/001-graph-isolation/                 # Feature specification
│   ├── spec.md                                # Requirements & user stories
│   ├── plan.md                                # Implementation plan
│   ├── data-model.md                          # Graph architecture
│   ├── quickstart.md                          # Testing guide
│   └── tasks.md                               # Implementation checklist
└── docker-compose.yaml                        # PostgreSQL + AGE + pgvector
```

## Testing

```bash
# Unit tests (11 tests including 8 isolation tests)
./mvnw test

# Integration tests with Testcontainers (9 tests)
./mvnw verify -DskipITs=false

# Isolation verification
./specs/001-graph-isolation/test-isolation.sh
```

**Test Coverage**: >80% branch coverage on graph isolation logic.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Development

### Running in Dev Mode

Live coding with automatic reload:

```bash
./mvnw quarkus:dev
```

> **Dev UI**: Available at <http://localhost:8080/q/dev/> in dev mode.

### Building for Production

```bash
# Standard JAR
./mvnw clean package

# Run production build
java -jar target/quarkus-app/quarkus-run.jar
```

### Creating Native Executable

```bash
# With GraalVM installed
./mvnw package -Dnative

# Using container build (no GraalVM needed)
./mvnw package -Dnative -Dquarkus.native.container-build=true

# Run native executable
./target/rag-saas-1.0-SNAPSHOT-runner
```

## Configuration

Key configuration in `application.properties`:

```properties
# Database
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/ragsaas
quarkus.datasource.username=postgres
quarkus.datasource.password=postgres

# AGE Graph Extension
age.graph.enabled=true
age.graph.namespace=ag_catalog

# LLM Integration (configure for your provider)
llm.api.base-url=http://localhost:11434  # Ollama example
llm.model.chat=llama3
llm.model.embedding=nomic-embed-text
```

## API Documentation

### Projects API

**Create Project**:
```bash
POST /api/projects
Content-Type: application/json

{"name": "Project Name", "description": "Optional description"}
```

**List Projects**:
```bash
GET /api/projects
```

**Delete Project** (cascades to graph + vectors):
```bash
DELETE /api/projects/{projectId}
```

### Documents API

**Upload Document**:
```bash
POST /api/projects/{projectId}/documents
Content-Type: multipart/form-data

file: <file>
type: PDF | DOCX | TXT | WEB
```

**List Documents**:
```bash
GET /api/projects/{projectId}/documents
```

### Chat API (RAG Query)

**Query with Graph Isolation**:
```bash
POST /api/projects/{projectId}/chat
Content-Type: application/json

{
  "query": "Your question here",
  "mode": "HYBRID"  # Options: NAIVE, LOCAL, GLOBAL, HYBRID, MIX
}
```

Response:
```json
{
  "response": "AI-generated answer using project's isolated graph",
  "sources": ["doc_id_1", "doc_id_2"],
  "mode": "HYBRID"
}
```

## Troubleshooting

### Graph Not Found Error

**Symptom**: `IllegalStateException: Graph not found for project`

**Cause**: Graph wasn't created during project creation

**Fix**:
```bash
# Verify graph exists in PostgreSQL
docker exec -it rag-saas-postgres psql -U postgres -d ragsaas \
  -c "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'graph_%';"

# Check logs for graph creation
docker-compose logs | grep "Creating graph for project"
```

### Cross-Project Data Leakage

**Symptom**: Query returns entities from other projects

**Diagnosis**: Check logs for graph routing:
```bash
# Should show: "Executing query on graph: graph_<project_uuid_prefix>"
docker-compose logs | grep "Executing query on graph"
```

**Fix**: Ensure all GraphStorage calls include correct `projectId` parameter.

### Performance Issues

**Target**: P95 <300ms for 2-hop traversals

**Diagnosis**:
```bash
# Check graph size
curl http://localhost:8080/api/projects/{projectId}/graph/stats
```

**Mitigation**:
- Graphs with >100K entities may need optimization
- Check for circular relationships causing infinite traversals
- Verify queries use projectId (not scanning all graphs)

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): A Jakarta REST implementation utilizing build time processing and
  Vert.x. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on
  it.
- REST Client ([guide](https://quarkus.io/guides/rest-client)): Call REST services
- Hibernate Validator ([guide](https://quarkus.io/guides/validation)): Validate object properties (field, getter) and
  method parameters for your beans (REST, CDI, Jakarta Persistence)
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplify your persistence code
  for Hibernate ORM via the active record or the repository pattern
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC

## Provided Code

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)

[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)

### REST Client

Invoke different services through REST with JSON

[Related guide section...](https://quarkus.io/guides/rest-client)

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
