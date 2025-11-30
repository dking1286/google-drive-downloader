# Plan: Address SyncEngine Implementation Issues

**Reference:** `specs/sync_engine_analysis_1.md`

## Overview

Address all 17 issues identified in the SyncEngine analysis, organized by priority. Key design decisions from user:
- Rename `data class SyncStatus` to `SyncStatusSnapshot` (resolve naming conflict)
- Use `Result<T>` for shared validation helpers
- Create new `dev.dking.googledrivedownloader.files` package for shared path/symlink validation

---

## Phase 1: Security Fixes (Critical + High Priority)

### 1.1 Create Shared Path Validation Package

**New Package:** `dev.dking.googledrivedownloader.files`

**New File:** `PathValidator.kt`

Extract validation logic from `GoogleDriveClientImpl` into reusable functions that return `Result<Unit>`:

```kotlin
package dev.dking.googledrivedownloader.files

object PathValidator {
    /**
     * Validates that outputPath is within baseDirectory (no path traversal).
     * @return Result.success if valid, Result.failure with descriptive error otherwise
     */
    fun validatePathWithinBase(outputPath: Path, baseDirectory: Path): Result<Unit>

    /**
     * Validates that no component of the path is a symbolic link.
     * @return Result.success if no symlinks, Result.failure with descriptive error otherwise
     */
    fun validateNoSymlinks(outputPath: Path, baseDirectory: Path): Result<Unit>

    /**
     * Combines both validations: path traversal and symlink checks.
     * @return Result.success if both pass, Result.failure with first error otherwise
     */
    fun validatePath(outputPath: Path, baseDirectory: Path): Result<Unit>
}
```

**New File:** `PathValidatorTest.kt` - comprehensive tests for the validator

### 1.2 Update GoogleDriveClientImpl to Use PathValidator

**File:** `GoogleDriveClientImpl.kt`

- Replace private `validateOutputPath()` and `validateNoSymlinks()` methods with calls to `PathValidator`
- Convert `Result.failure` to `IllegalArgumentException` to maintain current API contract

### 1.3 Add Path Validation to FileOperations

**File:** `sync/impl/FileOperations.kt`

Add validation calls at the start of:
- `downloadRegularFile()` - validate before writing
- `exportWorkspaceFile()` - validate before writing
- `createFolder()` - validate before creating directory

### 1.4 Use UUID-based Temp File Names in FileOperations

**File:** `sync/impl/FileOperations.kt`

Replace predictable temp file names:
```kotlin
// Before:
val tempPath = localPath.resolveSibling("${localPath.name}.tmp")

// After:
val tempPath = localPath.resolveSibling(".${UUID.randomUUID()}.download.tmp")
```

Apply to both `downloadRegularFile()` and `exportWorkspaceFile()`.

### 1.5 Fix Database Thread Safety

**File:** `sync/impl/DatabaseManager.kt`

Add a mutex or single-threaded dispatcher to serialize database access:

```kotlin
internal class DatabaseManager(databasePath: Path) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
    private val dbMutex = Mutex()

    suspend fun <T> withDatabase(block: () -> T): T = dbMutex.withLock {
        block()
    }

    // Or use a dedicated dispatcher:
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
}
```

---

## Phase 2: Naming and Type Fixes (Critical)

### 2.1 Rename SyncStatus Data Class

**File:** `sync/SyncEngine.kt`

Rename `data class SyncStatus` to `SyncStatusSnapshot`:
- Line 87: `data class SyncStatus` → `data class SyncStatusSnapshot`
- Line 38: Return type `Result<SyncStatus>` → `Result<SyncStatusSnapshot>`

**File:** `sync/impl/SyncEngineImpl.kt`

Update all references to `SyncStatus` data class to `SyncStatusSnapshot`.

### 2.2 Fix FileRecord.syncStatus Type

**File:** `sync/SyncEngine.kt`

Line 108: Change `val syncStatus: SyncStatus` to `val syncStatus: SyncStatus` where `SyncStatus` now correctly refers to the inner enum (after renaming the outer class, this becomes unambiguous). Or explicitly: `val syncStatus: FileRecord.SyncStatus`.

---

## Phase 3: Code Quality Fixes (High + Medium Priority)

### 3.1 Fix runBlocking Anti-Pattern

**File:** `sync/impl/SyncEngineImpl.kt`

Change progress callback from blocking to suspend:

```kotlin
// Before (in downloadPendingFiles):
val result = fileOps.downloadFile(file) { bytes, total ->
    runBlocking {
        onProgress(file.id, file.name, bytes, total)
    }
    lastBytes = bytes
}

// After: Make the callback suspend-friendly or use a channel
```

**Option A:** Make `FileOperations.downloadFile` accept a suspend callback
**Option B:** Use a Channel to communicate progress without blocking

### 3.2 Add Database Transaction Support

**File:** `sync/impl/DatabaseManager.kt`

Add transaction wrapper for atomic multi-step operations:

```kotlin
fun <T> transaction(block: () -> T): T {
    connection.autoCommit = false
    return try {
        val result = block()
        connection.commit()
        result
    } catch (e: Exception) {
        connection.rollback()
        throw e
    } finally {
        connection.autoCommit = true
    }
}
```

### 3.3 Wire Up or Remove resolvePathConflict()

**File:** `sync/impl/FileOperations.kt`

Either:
- **Wire up:** Call `resolvePathConflict()` in `buildLocalPath()` or before downloads
- **Remove:** Delete the dead code if duplicate handling isn't needed

**Recommendation:** Wire it up in `driveFileToRecord()` after building the initial path.

### 3.4 Handle Trashed Files Explicitly

**File:** `sync/impl/SyncEngineImpl.kt`

In incremental sync change processing, add explicit check for trashed files:

```kotlin
// Before:
if (change.removed || change.file == null) {

// After:
if (change.removed || change.file == null || change.file.trashed == true) {
```

**Note:** Need to verify if `DriveFile` has a `trashed` field. If not, may need to add it to the API layer.

---

## Phase 4: Test Coverage (Critical Gap)

### 4.1 Create DatabaseManagerTest

**New File:** `sync/impl/DatabaseManagerTest.kt`

Tests to include:
- Schema initialization creates all tables and indexes
- `createSyncRun()` returns valid ID, `getLastSyncRun()` retrieves it
- `upsertFile()` insert vs update behavior
- `updateFileStatus()` state transitions
- `getFile()` existing/non-existing
- `getFilesByStatus()` and `getFilesByStatuses()` filtering
- `getChildren()` with null/non-null parent
- `deleteFile()` removes record
- `getSyncStatistics()` aggregation correctness
- `saveChangeToken()` and `getChangeToken()` round-trip
- Connection lifecycle (close behavior)

### 4.2 Create FileOperationsTest

**New File:** `sync/impl/FileOperationsTest.kt`

Tests to include:
- `sanitizeFilename()`: slashes, null bytes, 255-byte truncation, UTF-8 edge cases
- `buildLocalPath()`: flat files, nested folders, Workspace files with extensions
- `resolvePathConflict()`: no conflict, single conflict, multiple conflicts
- `downloadRegularFile()`: success path (mocked client), failure path
- `exportWorkspaceFile()`: success with correct MIME type
- `createFolder()`: creates directory structure
- `downloadFile()`: routing to correct method based on MIME type
- `driveFileToRecord()`: all fields mapped correctly
- Path validation integration: rejects traversal attempts, rejects symlinks

### 4.3 Fix Weak resumeSync Test

**File:** `sync/impl/SyncEngineImplTest.kt`

Rewrite `resumeSync - with interruption` test to:
1. Manually set up database state with a "running" sync run
2. Insert files with PENDING/DOWNLOADING status
3. Call `resumeSync()` and verify it processes those files
4. Don't rely on exception rollback behavior

---

## Phase 5: Feature Implementation (High Priority)

### 5.1 Implement Shortcut Handling

**File:** `sync/impl/FileOperations.kt`

Add shortcut handling in `downloadFile()`:

```kotlin
suspend fun downloadFile(file: FileRecord, onProgress: ...): Result<Unit> {
    return when {
        file.mimeType == GOOGLE_SHORTCUT_MIME_TYPE -> createShortcut(file)
        file.isFolder -> createFolder(file)
        file.mimeType.startsWith(GOOGLE_WORKSPACE_PREFIX) -> exportWorkspaceFile(file, onProgress)
        else -> downloadRegularFile(file, onProgress)
    }
}

private fun createShortcut(file: FileRecord): Result<Unit> {
    // 1. Look up target file in database by shortcutTargetId
    // 2. Get target's local path
    // 3. Create symlink: Files.createSymbolicLink(shortcutPath, targetPath)
}
```

**Note:** Need to ensure `shortcutTargetId` is available in `FileRecord` (from `DriveFile.shortcutTargetId`).

### 5.2 Implement Partial Download Resume (Simple Restart)

For SyncEngine, use the same approach as GoogleDriveClientImpl:
- On resume, delete any existing `.tmp` files and restart downloads
- Don't implement HTTP Range resume (too complex for marginal benefit)

---

## Phase 6: Low Priority Improvements

### 6.1 Improve Filename Sanitization

**File:** `sync/impl/FileOperations.kt`

Enhance `sanitizeFilename()`:
- Handle multi-byte UTF-8 truncation properly (don't cut mid-character)
- Consider handling more problematic characters for cross-platform safety

### 6.2 Refactor SyncEngineImpl (Optional)

Consider extracting:
- `DownloadCoordinator` class for concurrent download logic
- Common sync template pattern for shared setup/teardown

**Recommendation:** Defer this to a future refactoring pass unless time permits.

### 6.3 Implement True Breadth-First Traversal (Optional)

Current approach works (relies on `Files.createDirectories()`). True BFS would be more correct but adds complexity.

**Recommendation:** Document current behavior, defer implementation unless issues arise.

---

## Implementation Order

| Order | Task | Priority | Estimated Effort |
|-------|------|----------|------------------|
| 1 | Create `PathValidator` in new `files` package | Critical | Medium |
| 2 | Update `GoogleDriveClientImpl` to use `PathValidator` | Critical | Small |
| 3 | Add path validation to `FileOperations` | Critical | Small |
| 4 | Rename `SyncStatus` → `SyncStatusSnapshot` | Critical | Small |
| 5 | Fix `FileRecord.syncStatus` type | Critical | Small |
| 6 | Fix database thread safety (add mutex) | Critical | Medium |
| 7 | UUID-based temp files in `FileOperations` | High | Small |
| 8 | Fix `runBlocking` anti-pattern | High | Medium |
| 9 | Create `DatabaseManagerTest` | Critical | Large |
| 10 | Create `FileOperationsTest` | Critical | Large |
| 11 | Fix weak `resumeSync` test | Medium | Small |
| 12 | Add transaction support to `DatabaseManager` | Medium | Medium |
| 13 | Wire up `resolvePathConflict()` | Medium | Small |
| 14 | Handle trashed files explicitly | Medium | Small |
| 15 | Implement shortcut handling | High | Medium |
| 16 | Improve filename sanitization | Low | Small |
| 17 | (Optional) Refactor SyncEngineImpl | Low | Large |

---

## Files to Create

| File | Purpose |
|------|---------|
| `src/main/kotlin/.../files/PathValidator.kt` | Shared path validation utilities |
| `src/test/kotlin/.../files/PathValidatorTest.kt` | Tests for PathValidator |
| `src/test/kotlin/.../sync/impl/DatabaseManagerTest.kt` | Tests for DatabaseManager |
| `src/test/kotlin/.../sync/impl/FileOperationsTest.kt` | Tests for FileOperations |

## Files to Modify

| File | Changes |
|------|---------|
| `src/main/kotlin/.../api/impl/GoogleDriveClientImpl.kt` | Use shared PathValidator |
| `src/main/kotlin/.../sync/SyncEngine.kt` | Rename SyncStatus, fix FileRecord type |
| `src/main/kotlin/.../sync/impl/SyncEngineImpl.kt` | Update SyncStatus refs, fix runBlocking |
| `src/main/kotlin/.../sync/impl/DatabaseManager.kt` | Add mutex, transactions |
| `src/main/kotlin/.../sync/impl/FileOperations.kt` | Path validation, UUID temps, shortcuts, conflict resolution |
| `src/test/kotlin/.../sync/impl/SyncEngineImplTest.kt` | Fix weak resumeSync test |

---

## Excluded from Plan (per user request)

- Database location hardcoding (acceptable as-is)