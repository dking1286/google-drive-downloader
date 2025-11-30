# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build          # Full build with tests
./gradlew test           # Run all tests
./gradlew ktlintCheck    # Check code style (enforced in CI)
./gradlew ktlintFormat   # Auto-fix code style issues
./gradlew nativeCompile  # Build GraalVM native image
```

Run a single test class:
```bash
./gradlew test --tests "dev.dking.googledrivedownloader.api.impl.RetryHandlerTest"
```

## Architecture

Three-layer architecture with dependency injection:

```
┌─────────────────────────────────────────────────────────────┐
│ CLI Layer (cli/)                                            │
│   Command parsing, progress display, config loading         │
├─────────────────────────────────────────────────────────────┤
│ Sync Layer (sync/)                                          │
│   SyncEngine orchestrates downloads, DatabaseManager        │
│   persists state to SQLite, FileOperations handles I/O      │
├─────────────────────────────────────────────────────────────┤
│ API Layer (api/)                                            │
│   GoogleDriveClient interface + impl, OAuth via             │
│   DriveServiceFactory/TokenManager, RetryHandler for        │
│   exponential backoff with jitter                           │
└─────────────────────────────────────────────────────────────┘
```

## Key Patterns

- **Result\<T\> return types** - All async operations return Result for explicit error handling
- **Sealed exception hierarchy** - Transient errors (429, 5xx) trigger retry; permanent errors (4xx) fail immediately
- **Flow\<SyncEvent\>** - SyncEngine emits events for progress tracking
- **Constructor injection** - Components accept dependencies as parameters for testability
- **Path traversal protection** - Download paths validated against baseDirectory

## Technology Stack

- Kotlin 2.2.20, JVM 21, Coroutines 1.9.0
- Google Drive API v3 with PKCE OAuth 2.0
- SQLite for local state, Kotlinx Serialization for JSON
- GraalVM Native Image support (optional)
- ktlint for code style enforcement

## Configuration Paths

- `~/.google-drive-downloader/config.json` - OAuth credentials (clientId, clientSecret)
- `~/.google-drive-downloader/tokens.json` - OAuth tokens (chmod 600)

## Testing

Tests use kotlinx.coroutines.test.runTest for async testing and MockK for mocking. Each test creates/cleans up temp directories.
