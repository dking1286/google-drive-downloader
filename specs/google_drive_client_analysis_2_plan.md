# Plan: Address GoogleDriveClient Implementation Gaps

## Overview
Address all issues identified in the TDD compliance review, security analysis, and test coverage gaps for the `dev.dking.googledrivedownloader.api` package.

---

## 1. TDD Compliance Fixes

### 1.1 Add MD5 Checksum Verification
**File:** `GoogleDriveClientImpl.kt`

- After downloading a file, compute MD5 hash of the downloaded content
- Compare against `md5Checksum` from file metadata
- **If mismatch: retry once**, then fail if second attempt also mismatches
- Note: Google Workspace exported files don't have MD5 checksums (skip verification for exports)

**Implementation:**
```kotlin
// After download completes, before atomic rename:
val downloadedMd5 = computeMd5(tempPath)
if (metadata.md5Checksum != null && downloadedMd5 != metadata.md5Checksum) {
    Files.deleteIfExists(tempPath)
    if (!isRetry) {
        logger.warn { "MD5 mismatch, retrying download" }
        return downloadFileInternal(fileId, outputPath, onProgress, isRetry = true)
    }
    throw ApiException("MD5 checksum mismatch after retry: expected ${metadata.md5Checksum}, got $downloadedMd5")
}
```

### 1.2 Simple Download Restart (instead of full resume)
**File:** `GoogleDriveClientImpl.kt`

- **Simple approach:** If temp file exists, delete it and restart download
- This is safer and simpler than HTTP Range resume
- Existing temp files indicate interrupted downloads that should be restarted cleanly

**Implementation:**
```kotlin
// At start of download, before creating temp file:
if (Files.exists(tempPath)) {
    logger.info { "Deleting existing temp file from interrupted download: $tempPath" }
    Files.deleteIfExists(tempPath)
}
```

### 1.3 Fix Progress Reporting for Unknown File Sizes
**File:** `GoogleDriveClientImpl.kt:224-230`

- Track actual bytes written to stream instead of calculating from percentage
- Use a counting output stream wrapper to track bytes

---

## 2. Security Fixes

### 2.1 Path Traversal Protection
**File:** `GoogleDriveClientImpl.kt`

- **Add `baseDirectory` as constructor parameter** for `GoogleDriveClientImpl`
- Validate that all `outputPath` arguments are within `baseDirectory`
- Normalize paths and check canonical path is under base directory

**Implementation:**
```kotlin
class GoogleDriveClientImpl(
    private val config: DriveClientConfig = DriveClientConfig(),
    private val serviceFactory: DriveServiceFactory,
    private val tokenManager: TokenManager,
    private val baseDirectory: Path,  // NEW: Required base directory
) : GoogleDriveClient {

    private fun validateOutputPath(outputPath: Path) {
        val normalizedOutput = outputPath.normalize().toAbsolutePath()
        val normalizedBase = baseDirectory.normalize().toAbsolutePath()
        require(normalizedOutput.startsWith(normalizedBase)) {
            "Output path $outputPath escapes base directory $baseDirectory"
        }
    }

    // Call validateOutputPath() at start of downloadFile() and exportFile()
}
```

### 2.2 Symlink Attack Protection
**File:** `GoogleDriveClientImpl.kt`

- Use `LinkOption.NOFOLLOW_LINKS` when checking paths
- Verify parent directory is not a symlink before creating directories
- Consider using `Files.createDirectories` with careful checks

### 2.3 Unpredictable Temp File Names
**File:** `GoogleDriveClientImpl.kt`

- Replace `filename.tmp` with random UUID-based temp names
- Pattern: `.<uuid>.download.tmp`

```kotlin
val tempPath = outputPath.parent.resolve(".${UUID.randomUUID()}.download.tmp")
```

### 2.4 Atomic Token File Permissions
**File:** `TokenManager.kt`

- Create file with restrictive permissions from the start using `PosixFilePermissions.asFileAttribute()`
- Or write to temp file with correct permissions, then atomic move

```kotlin
val permissions = PosixFilePermissions.fromString("rw-------")
val attrs = PosixFilePermissions.asFileAttribute(permissions)
Files.createFile(tokenPath, attrs)  // Create with permissions
Files.writeString(tokenPath, jsonString, StandardOpenOption.TRUNCATE_EXISTING)
```

---

## 3. Test Coverage Improvements

### 3.1 GoogleDriveClientImplTest Expansion
**File:** `GoogleDriveClientImplTest.kt`

Add tests with mocked `DriveServiceFactory`:

| Test Category | Specific Tests |
|---------------|----------------|
| listAllFiles success | Single page, multiple pages, empty result |
| listAllFiles field mapping | All FileField values map correctly |
| listChanges success | Changes with files, removed files, mixed |
| downloadFile success | Normal download, with progress callback |
| downloadFile MD5 verification | Match, mismatch, missing checksum |
| exportFile success | Normal export |
| Edge cases | Null parents, null md5, null size, shortcuts |
| Error handling | Mid-stream errors, temp file cleanup |

**Approach:** Create a mock `DriveServiceFactory` that returns a mocked `Drive` service.

### 3.2 RetryHandlerTest Additions
**File:** `RetryHandlerTest.kt`

Add missing HTTP status code tests:
- 500 Internal Server Error (transient)
- 502 Bad Gateway (transient)
- 504 Gateway Timeout (transient)
- 400 Bad Request (permanent)
- 403 Forbidden (permanent)

### 3.3 TokenManagerTest Additions
**File:** `TokenManagerTest.kt`

Add edge case tests:
- Null refresh token during save (should work)
- File permissions verification (on POSIX systems)
- Concurrent save/load operations

### 3.4 New DriveServiceFactoryTest
**New File:** `DriveServiceFactoryTest.kt`

Tests to add:
- `createAuthorizationFlow` returns valid flow with correct scopes
- `createDriveService` creates service with correct application name
- `createDriveServiceFromTokens` creates service with provided tokens
- Error handling in `authorize` (mock the interactive flow)

---

## 4. Implementation Order

**Phase 1: Security (high priority)**
1. Path traversal protection
2. Atomic token permissions
3. Unpredictable temp file names
4. Symlink protection

**Phase 2: TDD Compliance**
1. MD5 checksum verification
2. Progress reporting fix
3. Partial download resumption

**Phase 3: Test Coverage**
1. RetryHandlerTest additions (quick wins)
2. TokenManagerTest additions
3. DriveServiceFactoryTest (new file)
4. GoogleDriveClientImplTest expansion (largest effort)

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/main/kotlin/.../api/impl/GoogleDriveClientImpl.kt` | MD5 verification, resume support, progress fix, path validation, temp file naming |
| `src/main/kotlin/.../api/impl/TokenManager.kt` | Atomic permissions |
| `src/test/kotlin/.../api/impl/GoogleDriveClientImplTest.kt` | Major expansion with mocked tests |
| `src/test/kotlin/.../api/impl/RetryHandlerTest.kt` | Add 5 new status code tests |
| `src/test/kotlin/.../api/impl/TokenManagerTest.kt` | Add 3 edge case tests |
| `src/test/kotlin/.../api/impl/DriveServiceFactoryTest.kt` | **New file** |

---

## Design Decisions (Confirmed)

| Decision | Choice |
|----------|--------|
| MD5 verification failure | Retry once, then fail |
| Base directory config | Constructor parameter |
| Resume support | Simple restart (delete temp, redownload) |