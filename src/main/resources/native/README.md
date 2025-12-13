# Native SQLite Extensions

This directory contains platform-specific native libraries for SQLite extensions.

## Required Extensions

1. **sqlite-graph** (v0.1.0-alpha.0) - Graph database extension with Cypher support
   - Repository: https://github.com/agentflare-ai/sqlite-graph
   - License: MIT

2. **sqlite-vector** (v0.9.52) - Vector similarity search extension
   - Repository: https://github.com/sqliteai/sqlite-vector
   - License: Elastic License 2.0

## Directory Structure

```
native/
├── linux-x86_64/
│   ├── libgraph.so      # sqlite-graph for Linux
│   └── vector0.so       # sqlite-vector for Linux
├── darwin-x86_64/
│   ├── libgraph.dylib   # sqlite-graph for macOS Intel
│   └── vector0.dylib    # sqlite-vector for macOS Intel
└── darwin-aarch64/
    ├── libgraph.dylib   # sqlite-graph for macOS Apple Silicon
    └── vector0.dylib    # sqlite-vector for macOS Apple Silicon
```

## Download Instructions

### sqlite-graph

```bash
# Linux x86_64
curl -L -o src/main/resources/native/linux-x86_64/libgraph.so \
  https://github.com/agentflare-ai/sqlite-graph/releases/download/v0.1.0-alpha.0/libgraph-linux-x86_64.so

# macOS Intel
curl -L -o src/main/resources/native/darwin-x86_64/libgraph.dylib \
  https://github.com/agentflare-ai/sqlite-graph/releases/download/v0.1.0-alpha.0/libgraph-macos-x86_64.dylib

# macOS Apple Silicon
curl -L -o src/main/resources/native/darwin-aarch64/libgraph.dylib \
  https://github.com/agentflare-ai/sqlite-graph/releases/download/v0.1.0-alpha.0/libgraph-macos-aarch64.dylib
```

### sqlite-vector

```bash
# Linux x86_64
curl -L -o src/main/resources/native/linux-x86_64/vector0.so \
  https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-linux-x86_64.tar.gz

# macOS Intel
curl -L -o src/main/resources/native/darwin-x86_64/vector0.dylib \
  https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-macos-x86_64.tar.gz

# macOS Apple Silicon
curl -L -o src/main/resources/native/darwin-aarch64/vector0.dylib \
  https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-macos-aarch64.tar.gz
```

## Alternative: External Path Configuration

Instead of bundling libraries, you can configure an external path:

```properties
lightrag.storage.sqlite.extensions.path=/opt/sqlite/extensions
```

## Verification

After downloading, verify the libraries load correctly:

```bash
# Test sqlite-graph
sqlite3 :memory: "SELECT load_extension('/path/to/libgraph'); SELECT sqlite_version();"

# Test sqlite-vector
sqlite3 :memory: "SELECT load_extension('/path/to/vector0'); SELECT vec_version();"
```
