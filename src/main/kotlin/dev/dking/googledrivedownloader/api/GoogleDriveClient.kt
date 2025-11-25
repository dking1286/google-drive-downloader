package dev.dking.googledrivedownloader.api

import java.nio.file.Path
import java.time.Instant

/**
 * Client for interacting with the Google Drive API.
 * Handles OAuth 2.0 authentication, file listing, change detection, and file downloads.
 */
interface GoogleDriveClient {
    /**
     * Authenticate with Google Drive using OAuth 2.0 with PKCE.
     * @param forceReauth Force re-authentication even if valid tokens exist
     * @return Result indicating success or authentication failure
     */
    suspend fun authenticate(forceReauth: Boolean = false): Result<Unit>

    /**
     * Check if currently authenticated with valid, non-expired tokens.
     * @return true if authenticated and tokens are valid
     */
    fun isAuthenticated(): Boolean

    /**
     * Get a page token representing the current state of Google Drive ("right now").
     * This calls the Google Drive API to fetch a fresh token - it does NOT read from local storage.
     * Use this to initialize change tracking on first sync. The returned token should be stored
     * locally by the Sync Engine for use in subsequent incremental syncs.
     * @return Result containing the current start page token from the API, or error
     */
    suspend fun getStartPageToken(): Result<String>

    /**
     * List all files and folders in Google Drive.
     * Handles pagination internally and returns complete list.
     * @param fields Set of fields to retrieve for each file
     * @return Result containing list of all DriveFile objects or error
     */
    suspend fun listAllFiles(fields: Set<FileField>): Result<List<DriveFile>>

    /**
     * List changes since a given page token.
     * @param pageToken The page token from which to start listing changes
     * @return Result containing ChangeList with changes and new token, or error
     */
    suspend fun listChanges(pageToken: String): Result<ChangeList>

    /**
     * Download a regular (non-Workspace) file to the specified path.
     * @param fileId Google Drive file ID
     * @param outputPath Local path where file should be saved
     * @param onProgress Optional callback for download progress updates
     * @return Result indicating success or download failure
     */
    suspend fun downloadFile(
        fileId: String,
        outputPath: Path,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<Unit>

    /**
     * Export a Google Workspace file to a standard format.
     * @param fileId Google Drive file ID
     * @param exportMimeType Target MIME type for export (e.g., DOCX, XLSX)
     * @param outputPath Local path where exported file should be saved
     * @return Result indicating success or export failure
     */
    suspend fun exportFile(
        fileId: String,
        exportMimeType: String,
        outputPath: Path
    ): Result<Unit>
}

/**
 * Represents a file or folder in Google Drive.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parentId: String?,
    val md5Checksum: String?,
    val modifiedTime: Instant,
    val size: Long?,
    val isFolder: Boolean,
    val shortcutTargetId: String? = null
)

/**
 * Represents a list of changes from the Google Drive API.
 */
data class ChangeList(
    val changes: List<FileChange>,
    val newStartPageToken: String
)

/**
 * Represents a single change (add/modify/remove) to a file.
 */
data class FileChange(
    val fileId: String,
    val removed: Boolean,
    val file: DriveFile?
)

/**
 * Fields that can be requested when listing files.
 */
enum class FileField {
    ID, NAME, MIME_TYPE, PARENTS, MD5_CHECKSUM, MODIFIED_TIME, SIZE, SHORTCUT_DETAILS
}

/**
 * Configuration for the Google Drive Client.
 */
data class DriveClientConfig(
    val retryAttempts: Int = 3,
    val retryDelaySeconds: Int = 5
)
