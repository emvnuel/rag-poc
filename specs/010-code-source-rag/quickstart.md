# Quickstart: Code Source RAG

**Feature**: 010-code-source-rag  
**Date**: 2025-12-13

## Prerequisites

1. Running RAG SaaS instance (PostgreSQL or SQLite backend)
2. Project created
3. `curl` or similar HTTP client

## Quick Test: Upload and Query Code

### Step 1: Create a Test Project

```bash
# Create a new project
PROJECT_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "Code Test Project"}')

PROJECT_ID=$(echo $PROJECT_RESPONSE | jq -r '.id')
echo "Created project: $PROJECT_ID"
```

### Step 2: Upload a Code File

```bash
# Create a sample Java file
cat > /tmp/UserService.java << 'EOF'
package com.example.service;

import com.example.repository.UserRepository;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service for managing user operations.
 * Handles user creation, retrieval, and authentication.
 */
@ApplicationScoped
public class UserService {
    
    @Inject
    UserRepository userRepository;
    
    /**
     * Creates a new user with the given details.
     * 
     * @param name the user's display name
     * @param email the user's email address
     * @return the created user's ID
     */
    public String createUser(String name, String email) {
        validateEmail(email);
        User user = new User(name, email);
        return userRepository.save(user).getId();
    }
    
    /**
     * Finds a user by their email address.
     */
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new UserNotFoundException(email));
    }
    
    private void validateEmail(String email) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Invalid email: " + email);
        }
    }
}
EOF

# Upload the file
curl -X POST "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents" \
  -F "file=@/tmp/UserService.java"
```

### Step 3: Wait for Processing

```bash
# Check document status (wait for PROCESSED)
curl -s "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents" | jq '.[] | {fileName, status}'
```

### Step 4: Query the Code

```bash
# Ask about the code
curl -s -X POST "http://localhost:8080/api/v1/chat" \
  -H "Content-Type: application/json" \
  -d "{
    \"projectId\": \"${PROJECT_ID}\",
    \"message\": \"What methods are available in the UserService class?\",
    \"history\": []
  }" | jq '.response'
```

## Expected Results

### Document Metadata

After upload, the document should have code-specific metadata:

```json
{
  "language": "java",
  "languageDetectionMethod": "extension",
  "fileExtension": ".java",
  "lineCount": 42,
  "characterCount": 1156,
  "encoding": "UTF-8",
  "imports": [
    "com.example.repository.UserRepository",
    "jakarta.inject.Inject",
    "jakarta.enterprise.context.ApplicationScoped"
  ],
  "topLevelDeclarations": [
    {"type": "class", "name": "UserService", "line": 12}
  ]
}
```

### Extracted Entities

The system should extract code entities:

| Entity Name | Type | Description |
|-------------|------|-------------|
| `UserService` | CLASS | Service for managing user operations |
| `createUser` | FUNCTION | Creates a new user with the given details |
| `findByEmail` | FUNCTION | Finds a user by their email address |
| `validateEmail` | FUNCTION | Private method for email validation |
| `com.example.repository.UserRepository` | MODULE | Imported dependency |

### Extracted Relationships

| Source | Relation | Target |
|--------|----------|--------|
| `UserService` | IMPORTS | `UserRepository` |
| `UserService` | DEFINES | `createUser` |
| `UserService` | DEFINES | `findByEmail` |
| `createUser` | CALLS | `validateEmail` |
| `createUser` | CALLS | `userRepository.save` |

### Query Response

The chat response should:
1. Reference the `UserService` class
2. List the methods: `createUser`, `findByEmail`
3. Include source attribution (e.g., "from UserService.java")

## Testing Binary File Rejection

```bash
# Create a binary file with .java extension
echo -e '\x7F\x45\x4C\x46' > /tmp/fake.java

# Attempt to upload - should be rejected
curl -X POST "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents" \
  -F "file=@/tmp/fake.java"

# Expected response:
# {
#   "error": "BINARY_FILE_REJECTED",
#   "message": "Cannot process binary file. Only text-based source code files are supported."
# }
```

## Testing Multiple Languages

```bash
# Python file
cat > /tmp/calculator.py << 'EOF'
"""Simple calculator module."""

def add(a: float, b: float) -> float:
    """Add two numbers."""
    return a + b

def subtract(a: float, b: float) -> float:
    """Subtract b from a."""
    return a - b

class Calculator:
    """Calculator class with history."""
    
    def __init__(self):
        self.history = []
    
    def calculate(self, operation: str, a: float, b: float) -> float:
        """Perform calculation and store in history."""
        result = eval(f"{a} {operation} {b}")
        self.history.append((operation, a, b, result))
        return result
EOF

curl -X POST "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents" \
  -F "file=@/tmp/calculator.py"

# TypeScript file
cat > /tmp/api.ts << 'EOF'
interface User {
  id: string;
  name: string;
  email: string;
}

async function fetchUsers(): Promise<User[]> {
  const response = await fetch('/api/users');
  return response.json();
}

export class UserAPI {
  private baseUrl: string;
  
  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }
  
  async getUser(id: string): Promise<User> {
    const response = await fetch(`${this.baseUrl}/users/${id}`);
    return response.json();
  }
}
EOF

curl -X POST "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents" \
  -F "file=@/tmp/api.ts"
```

## Cross-Language Query

```bash
# Query that spans multiple languages
curl -s -X POST "http://localhost:8080/api/v1/chat" \
  -H "Content-Type: application/json" \
  -d "{
    \"projectId\": \"${PROJECT_ID}\",
    \"message\": \"What classes are defined in this project and what do they do?\",
    \"history\": []
  }" | jq '.response'
```

Expected: Response should mention `UserService` (Java), `Calculator` (Python), and `UserAPI` (TypeScript).

## Cleanup

```bash
# Delete the test project
curl -X DELETE "http://localhost:8080/api/v1/projects/${PROJECT_ID}"
```

## Troubleshooting

### Document Stuck in PROCESSING

Check the logs for entity extraction errors:
```bash
docker-compose logs app | grep -i "entity\|extraction\|error"
```

### Code Not Detected as Code

Verify the file extension is in the supported list. Check document metadata:
```bash
curl -s "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents/{docId}" | jq '.metadata'
```

### Low Retrieval Quality

If code queries return irrelevant results:
1. Check if document status is `PROCESSED`
2. Verify chunks were created with code metadata
3. Try more specific queries that reference function/class names
