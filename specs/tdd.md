# GDrive Sync — Technical Design Document

**Project:** Google Drive Incremental Backup Utility
**Language:** Kotlin (JVM) with GraalVM Native Image
**Target Platform:** Ubuntu Linux (native binary)
**Version:** 1.0 Draft

---

## 1. Executive Summary

GDrive Sync is a command-line utility that incrementally downloads all files from a user's Google Drive to their local filesystem. The program compiles to a native Linux binary with no JVM runtime dependency, supports resumable operations, and only downloads files that have changed since the last synchronization.

---

## 2. Requirements

### 2.1 Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-1 | Download all files from a user's Google Drive to a local directory |
| FR-2 | Support incremental sync — only download new or modified files |
| FR-3 | Resume interrupted downloads without re-downloading completed files |
| FR-4 | Preserve Google Drive folder hierarchy locally |
| FR-5 | Export Google Workspace files (Docs, Sheets, Slides) to standard formats |
| FR-6 | Authenticate using OAuth 2.0 via the Google Drive API |

### 2.2 Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR-1 | Compile to native binary using GraalVM Native Image (no JVM runtime dependency) |
| NFR-2 | Run on Ubuntu Linux (x86_64) |
| NFR-3 | Handle large Drive accounts (100,000+ files) |
| NFR-4 | Provide progress feedback during sync operations |
| NFR-5 | Log operations for debugging and audit purposes |

---

## 3. Architecture Overview

### 3.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        GDrive Sync CLI                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Command   │  │   Config    │  │      Progress           │  │
│  │   Parser    │  │   Manager   │  │      Reporter           │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                       Sync Engine                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  Change     │  │  Download   │  │      State              │  │
│  │  Detector   │  │  Manager    │  │      Manager            │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                      Google Drive Client                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   OAuth     │  │   Files     │  │      Export             │  │
│  │   Handler   │  │   API       │  │      Handler            │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                      Platform Layer                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   HTTP      │  │   File      │  │      JSON               │  │
│  │   Client    │  │   System    │  │      Parser             │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Component Descriptions

**Command Parser:** Parses CLI arguments and dispatches to appropriate handlers. Supports commands like `sync`, `auth`, `status`, and `reset`.

**Config Manager:** Manages configuration including OAuth credentials path, download directory, export format preferences, and concurrency settings.

**Progress Reporter:** Displays real-time progress information including files processed, bytes downloaded, and estimated time remaining.

**Change Detector:** Compares remote file metadata against local state to identify files requiring download. Uses Google Drive's `modifiedTime` and file checksums.

**Download Manager:** Orchestrates file downloads with support for concurrent transfers, retry logic, and partial download resumption.

**State Manager:** Persists sync state to enable incremental updates and crash recovery. Manages the local state database.

**OAuth Handler:** Implements OAuth 2.0 authorization code flow with PKCE. Manages token storage, refresh, and revocation.

**Files API:** Wrapper around Google Drive Files API for listing, metadata retrieval, and content download.

**Export Handler:** Converts Google Workspace files (Docs, Sheets, Slides) to downloadable formats (DOCX, XLSX, PPTX, PDF).

---

## 4. Data Design

### 4.1 Local State Database

The application maintains a SQLite database to track sync state. SQLite is chosen for its single-file simplicity, ACID compliance, and native compatibility through sqlite-jdbc with automatic GraalVM JNI configuration.

**Location:** `~/.gdrive-sync/state.db`

#### Schema

```sql
-- Tracks the last known state of each file
CREATE TABLE files (
    id              TEXT PRIMARY KEY,    -- Google Drive file ID
    name            TEXT NOT NULL,
    mime_type       TEXT NOT NULL,
    parent_id       TEXT,                -- Parent folder ID (null for root)
    local_path      TEXT,                -- Relative path from sync root
    remote_md5      TEXT,                -- MD5 checksum from Drive
    modified_time   TEXT NOT NULL,       -- ISO 8601 timestamp
    size            INTEGER,             -- File size in bytes
    is_folder       INTEGER NOT NULL,    -- Boolean: 1 = folder, 0 = file
    sync_status     TEXT NOT NULL,       -- pending, downloading, complete, error
    last_synced     TEXT,                -- ISO 8601 timestamp of last sync
    error_message   TEXT                 -- Last error if sync_status = error
);

-- Tracks overall sync operations
CREATE TABLE sync_runs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at      TEXT NOT NULL,
    completed_at    TEXT,
    status          TEXT NOT NULL,       -- running, completed, failed, interrupted
    files_processed INTEGER DEFAULT 0,
    bytes_downloaded INTEGER DEFAULT 0,
    start_page_token TEXT,               -- For resuming change detection
    error_message   TEXT
);

-- Stores the page token for incremental change detection
CREATE TABLE change_tokens (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    page_token      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- Index for efficient parent lookups during tree traversal
CREATE INDEX idx_files_parent ON files(parent_id);
CREATE INDEX idx_files_status ON files(sync_status);
```

### 4.2 Configuration File

**Location:** `~/.gdrive-sync/config.json`

```json
{
  "download_directory": "/home/user/GoogleDrive",
  "export_formats": {
    "application/vnd.google-apps.document": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.google-apps.spreadsheet": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.google-apps.presentation": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.google-apps.drawing": "image/png"
  },
  "max_concurrent_downloads": 4,
  "retry_attempts": 3,
  "retry_delay_seconds": 5,
  "log_level": "info"
}
```

### 4.3 OAuth Token Storage

**Location:** `~/.gdrive-sync/tokens.json` (file permissions: 600)

```json
{
  "access_token": "ya29.a0AfH6SM...",
  "refresh_token": "1//0eXx...",
  "token_type": "Bearer",
  "expires_at": "2025-05-15T14:30:00Z",
  "scope": "https://www.googleapis.com/auth/drive.readonly"
}
```

---

## 5. Google Drive API Integration

### 5.1 Required API Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /drive/v3/files` | List files and folders with metadata |
| `GET /drive/v3/files/{fileId}` | Get specific file metadata |
| `GET /drive/v3/files/{fileId}?alt=media` | Download file content |
| `GET /drive/v3/files/{fileId}/export` | Export Google Workspace files |
| `GET /drive/v3/changes/startPageToken` | Get initial change token |
| `GET /drive/v3/changes` | List changes since last sync |

### 5.2 OAuth 2.0 Configuration

**Scopes Required:**
- `https://www.googleapis.com/auth/drive.readonly` — Read-only access to all files

**Authorization Flow:**

1. Application generates PKCE code verifier and challenge
2. User is directed to Google's authorization URL in their browser
3. Application starts local HTTP server on `localhost:8085` to receive callback
4. User grants permission, Google redirects with authorization code
5. Application exchanges code for access and refresh tokens
6. Tokens are stored locally with restricted file permissions

### 5.3 Rate Limiting and Quotas

Google Drive API has the following default quotas:

| Quota | Limit |
|-------|-------|
| Queries per day | 1,000,000,000 |
| Queries per 100 seconds per user | 1,000 |
| Queries per 100 seconds | 10,000 |

The application implements exponential backoff when receiving `429 Too Many Requests` or `503 Service Unavailable` responses.

---

## 6. Sync Algorithm

### 6.1 Initial Sync (Full Download)

```
PROCEDURE InitialSync():
    page_token = GetStartPageToken()
    
    // Phase 1: Build file tree
    files = []
    next_page = null
    REPEAT:
        response = ListFiles(page_token=next_page, 
                            fields="id,name,mimeType,parents,md5Checksum,modifiedTime,size")
        files.append(response.files)
        next_page = response.nextPageToken
    UNTIL next_page IS NULL
    
    // Phase 2: Insert into database
    FOR file IN files:
        INSERT INTO files (id, name, ..., sync_status='pending')
    
    // Phase 3: Download files (breadth-first to create folders first)
    queue = GetRootFolders()
    WHILE queue NOT EMPTY:
        item = queue.dequeue()
        IF item.is_folder:
            CreateLocalDirectory(item.local_path)
            MarkComplete(item.id)
            queue.enqueue(GetChildren(item.id))
        ELSE:
            DownloadFile(item)
            MarkComplete(item.id)
    
    // Phase 4: Store change token for incremental sync
    SaveChangeToken(page_token)
```

### 6.2 Incremental Sync

```
PROCEDURE IncrementalSync():
    saved_token = GetSavedChangeToken()
    
    // Get all changes since last sync
    changes = []
    next_page = saved_token
    REPEAT:
        response = ListChanges(page_token=next_page)
        changes.append(response.changes)
        next_page = response.nextPageToken
        new_start_token = response.newStartPageToken
    UNTIL next_page IS NULL
    
    // Process changes
    FOR change IN changes:
        IF change.removed OR change.file.trashed:
            MarkForDeletion(change.fileId)
        ELSE IF IsNewFile(change.fileId):
            InsertFile(change.file, sync_status='pending')
        ELSE IF IsModified(change.file):
            UpdateFile(change.file, sync_status='pending')
    
    // Download pending files
    DownloadPendingFiles()
    
    // Optionally delete local files marked for deletion
    IF config.delete_removed_files:
        DeleteMarkedFiles()
    
    // Update change token
    SaveChangeToken(new_start_token)
```

### 6.3 Resume After Interruption

```
PROCEDURE ResumeSync():
    // Check for incomplete sync run
    last_run = GetLastSyncRun()
    
    IF last_run.status == 'running' OR last_run.status == 'interrupted':
        // Resume from where we left off
        pending_files = SELECT * FROM files WHERE sync_status IN ('pending', 'downloading')
        
        FOR file IN pending_files:
            IF file.sync_status == 'downloading':
                // Check for partial download
                partial_path = GetPartialPath(file)
                IF EXISTS(partial_path):
                    ResumeDownload(file, partial_path)
                ELSE:
                    DownloadFile(file)
            ELSE:
                DownloadFile(file)
        
        MarkSyncComplete(last_run.id)
    ELSE:
        // Start new incremental sync
        IncrementalSync()
```

---

## 7. File Handling

### 7.1 Regular Files

Regular files (non-Google Workspace) are downloaded directly using the `alt=media` parameter. The download process:

1. Create parent directories if they don't exist
2. Begin download to a temporary file (`filename.tmp`)
3. Verify MD5 checksum matches remote
4. Atomic rename to final filename
5. Update database record

### 7.2 Google Workspace Files

Google Workspace files (Docs, Sheets, Slides, etc.) cannot be downloaded directly. They must be exported to a standard format:

| Google MIME Type | Export Format | Extension |
|------------------|---------------|-----------|
| `application/vnd.google-apps.document` | DOCX | `.docx` |
| `application/vnd.google-apps.spreadsheet` | XLSX | `.xlsx` |
| `application/vnd.google-apps.presentation` | PPTX | `.pptx` |
| `application/vnd.google-apps.drawing` | PNG | `.png` |
| `application/vnd.google-apps.form` | (not exportable) | — |
| `application/vnd.google-apps.site` | (not exportable) | — |

### 7.3 Shortcuts and Links

Google Drive shortcuts are handled by:
1. Detecting `application/vnd.google-apps.shortcut` MIME type
2. Resolving the target file ID from `shortcutDetails.targetId`
3. Creating a local symlink pointing to the target file's local path

### 7.4 Filename Sanitization

Google Drive allows characters that are invalid on Linux filesystems. The application sanitizes filenames:

- Replace `/` with `_`
- Replace null bytes with `_`
- Truncate names exceeding 255 bytes (UTF-8)
- Handle duplicate names by appending ` (1)`, ` (2)`, etc.

---

## 8. Error Handling and Recovery

### 8.1 Transient Errors

Network timeouts, rate limits, and temporary server errors are handled with exponential backoff:

```
PROCEDURE RetryWithBackoff(operation, max_attempts=3):
    delay = 1 second
    FOR attempt IN 1..max_attempts:
        TRY:
            RETURN operation()
        CATCH TransientError:
            IF attempt == max_attempts:
                THROW
            Sleep(delay + random_jitter)
            delay = delay * 2
```

### 8.2 Permanent Errors

Files that fail after all retry attempts are marked with `sync_status='error'` and the error message is recorded. The sync continues with remaining files. A summary of failed files is displayed at completion.

### 8.3 Crash Recovery

The application is designed for crash recovery through:

1. **Atomic file writes:** Downloads go to `.tmp` files, then atomically renamed
2. **Database transactions:** State updates are wrapped in transactions
3. **Idempotent operations:** Re-running produces the same result
4. **Progress checkpointing:** Sync run progress is persisted regularly

---

## 9. GraalVM Native Image Considerations

### 9.1 Platform Libraries

GraalVM Native Image provides access to the mature JVM ecosystem while still producing native binaries. The following libraries are used:

| Capability | Library/Approach |
|------------|------------------|
| HTTP Client | OkHttp 4.12.0 (official GraalVM support) |
| Google Drive API | google-api-client 2.7.0 + google-api-services-drive (with native-image metadata) |
| JSON Parsing | kotlinx.serialization 1.7.3 (reflection-free) |
| SQLite | sqlite-jdbc 3.47.0.0 (automatic JNI configuration) |
| File I/O | Java NIO (Files, Paths) |
| OAuth 2.0 | google-oauth-client-jetty 1.36.0 |
| CLI Parsing | Picocli 4.7.6 (excellent GraalVM support) |

### 9.2 Build Configuration

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.graalvm.buildtools.native") version "0.11.1"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // HTTP & Google APIs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")
    implementation("com.google.cloud:native-image-support:0.21.0")

    // Database & serialization
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // CLI & utilities
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
}

application {
    mainClass.set("dev.dking.gdrivesync.MainKt")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("gdrive-sync")
            mainClass.set("dev.dking.gdrivesync.MainKt")

            buildArgs.addAll(listOf(
                "--enable-http",
                "--enable-https",
                "--enable-url-protocols=http,https",
                "--enable-all-security-services",
                "-O3",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=com.google.api.client",
                "--initialize-at-run-time=com.google.api.client.http.javanet.NetHttpTransport",
                "--gc=serial"
            ))

            verbose.set(true)
        }
    }

    // GraalVM agent for automatic metadata generation
    agent {
        defaultMode.set("standard")
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting.set(true)
        }
    }

    binaries.all {
        resources.autodetect()
    }
}

kotlin {
    jvmToolchain(21)
}
```

### 9.3 Native Image Metadata

GraalVM Native Image requires metadata for reflection, JNI, and resources. This is handled through:

**Automatic Metadata:**
- Google's native-image-support library provides pre-configured metadata for Google APIs
- sqlite-jdbc 3.40.1.0+ includes automatic JNI configuration
- Picocli annotations are automatically processed

**GraalVM Agent:**
During development, run with the agent to capture dynamic behavior:
```bash
./gradlew -Pagent run --args="auth"
./gradlew -Pagent run --args="sync --full"
./gradlew -Pagent test
```

**Metadata Location:**
- `src/main/resources/META-INF/native-image/reflect-config.json`
- `src/main/resources/META-INF/native-image/resource-config.json`
- `src/main/resources/META-INF/native-image/jni-config.json`
- `src/main/resources/META-INF/native-image/proxy-config.json`

### 9.4 Development Workflow

**Fast Iteration (JVM Mode):**
```bash
./gradlew run --args="sync"
```

**Native Binary Compilation:**
```bash
./gradlew nativeCompile
./build/native/nativeCompile/gdrive-sync --version
```

**Native Tests:**
```bash
./gradlew nativeTest
```

**Benefits:**
- Development uses standard JVM for fast iteration
- Official Google Drive API client with comprehensive documentation
- No C interop complexity
- Better IDE support and debugging
- Automatic dependency updates via Gradle

**Trade-offs:**
- Binary size: 40-80MB (vs 5-10MB with Kotlin/Native)
- Build time: 2-5 minutes (vs 30 seconds)
- Requires GraalVM for builds (standard toolchain)

---

## 10. Command-Line Interface

### 10.1 Commands

```
gdrive-sync <command> [options]

Commands:
  auth        Authenticate with Google Drive
  sync        Synchronize files from Google Drive
  status      Show sync status and statistics
  reset       Clear local state and start fresh

Global Options:
  --config <path>     Path to config file (default: ~/.gdrive-sync/config.json)
  --verbose, -v       Enable verbose output
  --quiet, -q         Suppress non-error output
  --help, -h          Show help message
  --version           Show version information
```

### 10.2 Command Details

**auth:**
```
gdrive-sync auth [--force]

Options:
  --force    Re-authenticate even if valid tokens exist
```

**sync:**
```
gdrive-sync sync [options]

Options:
  --full              Force full sync (ignore change tokens)
  --dry-run           Show what would be downloaded without downloading
  --include <pattern> Only sync files matching glob pattern
  --exclude <pattern> Skip files matching glob pattern
  --max-size <bytes>  Skip files larger than specified size
```

**status:**
```
gdrive-sync status

Output:
  Last sync: 2025-05-15 10:30:00
  Files tracked: 15,432
  Total size: 45.2 GB
  Pending: 0
  Errors: 3
```

---

## 11. Security Considerations

### 11.1 Token Storage

OAuth tokens are stored with restrictive permissions (`chmod 600`) to prevent unauthorized access. The refresh token enables long-term access and must be protected.

### 11.2 Scope Minimization

The application requests only `drive.readonly` scope, preventing any modification to the user's Google Drive even if tokens are compromised.

### 11.3 Local Data

Downloaded files inherit the user's umask settings. Sensitive files from Drive should be protected by appropriate local filesystem permissions.

### 11.4 PKCE for OAuth

The OAuth flow uses PKCE (Proof Key for Code Exchange) to prevent authorization code interception attacks, which is especially important for native applications.

---

## 12. Testing Strategy

### 12.1 Unit Tests

- JSON parsing for API responses
- Filename sanitization logic
- Change detection algorithms
- Retry/backoff logic
- Path resolution and tree building

### 12.2 Integration Tests

- OAuth flow with test credentials
- API request/response handling
- SQLite state management
- File download and verification

### 12.3 End-to-End Tests

- Full sync of test Drive account
- Incremental sync with simulated changes
- Interrupt and resume scenarios
- Large file handling
- Special character handling in filenames

---

## 13. Project Structure

```
gdrive-sync/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── dev/dking/gdrivesync/
│   │   │       ├── Main.kt
│   │   │       ├── cli/
│   │   │       │   ├── GDriveSyncCommand.kt
│   │   │       │   ├── AuthCommand.kt
│   │   │       │   ├── SyncCommand.kt
│   │   │       │   ├── StatusCommand.kt
│   │   │       │   ├── ResetCommand.kt
│   │   │       │   └── ProgressReporter.kt
│   │   │       ├── config/
│   │   │       │   ├── ConfigManager.kt
│   │   │       │   └── AppConfig.kt
│   │   │       ├── api/
│   │   │       │   ├── DriveClient.kt
│   │   │       │   ├── OAuthHandler.kt
│   │   │       │   ├── TokenStorage.kt
│   │   │       │   ├── RateLimiter.kt
│   │   │       │   └── RetryPolicy.kt
│   │   │       ├── sync/
│   │   │       │   ├── SyncEngine.kt
│   │   │       │   ├── ChangeDetector.kt
│   │   │       │   ├── DownloadManager.kt
│   │   │       │   └── FileDownloader.kt
│   │   │       ├── state/
│   │   │       │   ├── StateManager.kt
│   │   │       │   ├── Database.kt
│   │   │       │   └── models/
│   │   │       │       ├── FileRecord.kt
│   │   │       │       └── SyncRun.kt
│   │   │       ├── export/
│   │   │       │   ├── ExportHandler.kt
│   │   │       │   └── MimeTypeMapper.kt
│   │   │       └── util/
│   │   │           ├── FileUtils.kt
│   │   │           └── FilenameSanitizer.kt
│   │   └── resources/
│   │       └── META-INF/
│   │           └── native-image/
│   │               ├── reflect-config.json
│   │               ├── resource-config.json
│   │               ├── jni-config.json
│   │               └── proxy-config.json
│   └── test/
│       └── kotlin/
│           └── dev/dking/gdrivesync/
│               └── ... (test files)
```

---

## 14. Development Milestones

| Phase | Milestone | Description |
|-------|-----------|-------------|
| 1 | Project Setup | GraalVM plugin configuration, dependencies, basic CLI |
| 2 | OAuth Implementation | Full OAuth 2.0 + PKCE flow with google-oauth-client |
| 3 | API Client | Drive API wrapper using official Google client libraries |
| 4 | State Management | SQLite integration with sqlite-jdbc, schema, CRUD operations |
| 5 | Initial Sync | Full download with folder hierarchy preservation |
| 6 | Incremental Sync | Change detection and delta downloads |
| 7 | Resume Support | Crash recovery and partial download resumption |
| 8 | Export Handling | Google Workspace file conversion |
| 9 | Polish | Progress reporting, logging, error messages |
| 10 | Native Image Optimization | Metadata collection, binary optimization, testing |
| 11 | Testing & Release | Comprehensive testing, documentation, packaging |

---

## 15. Known Limitations

- **No two-way sync:** This tool only downloads from Drive; local changes are not uploaded
- **No real-time sync:** Synchronization is manual/scheduled, not continuous
- **No shared drive support:** Initial version supports only "My Drive"
- **Single account:** One Google account per installation
- **Linux only:** Native binary targets Ubuntu x86_64

---

## 16. Future Enhancements

Potential features for future versions:

- Shared/Team Drive support
- Multiple account support
- Selective folder sync
- Bandwidth throttling
- Daemon mode with file watching
- macOS and Windows builds
- Two-way synchronization
- Encryption at rest for sensitive files

---

## Appendix A: Google Drive API Response Examples

### File List Response

```json
{
  "kind": "drive#fileList",
  "nextPageToken": "...",
  "files": [
    {
      "kind": "drive#file",
      "id": "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms",
      "name": "Project Proposal.docx",
      "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "parents": ["0B1234567890"],
      "modifiedTime": "2025-05-10T14:30:00.000Z",
      "md5Checksum": "d41d8cd98f00b204e9800998ecf8427e",
      "size": "15432"
    }
  ]
}
```

### Changes Response

```json
{
  "kind": "drive#changeList",
  "nextPageToken": "...",
  "newStartPageToken": "12345",
  "changes": [
    {
      "kind": "drive#change",
      "type": "file",
      "changeType": "file",
      "time": "2025-05-15T10:00:00.000Z",
      "removed": false,
      "fileId": "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms",
      "file": {
        "id": "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms",
        "name": "Updated Document.docx",
        "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "modifiedTime": "2025-05-15T10:00:00.000Z"
      }
    }
  ]
}
```

---

## Appendix B: References

- [Google Drive API v3 Documentation](https://developers.google.com/drive/api/v3/reference)
- [GraalVM Native Image Documentation](https://www.graalvm.org/latest/reference-manual/native-image/)
- [GraalVM Native Build Tools (Gradle)](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)
- [Google Cloud Native Image Support](https://github.com/GoogleCloudPlatform/native-image-support-java)
- [OAuth 2.0 for Mobile & Desktop Apps](https://developers.google.com/identity/protocols/oauth2/native-app)
- [PKCE RFC 7636](https://tools.ietf.org/html/rfc7636)