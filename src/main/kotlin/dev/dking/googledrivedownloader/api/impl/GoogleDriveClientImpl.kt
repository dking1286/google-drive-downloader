package dev.dking.googledrivedownloader.api.impl

import dev.dking.googledrivedownloader.api.ChangeList
import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.DriveFile
import dev.dking.googledrivedownloader.api.FileField
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import java.nio.file.Path

/**
 * Boilerplate implementation of GoogleDriveClient that returns empty/default values.
 * This is a placeholder for the actual implementation.
 */
class GoogleDriveClientImpl(
    private val config: DriveClientConfig = DriveClientConfig()
) : GoogleDriveClient {

    override suspend fun authenticate(forceReauth: Boolean): Result<Unit> {
        return Result.success(Unit)
    }

    override fun isAuthenticated(): Boolean {
        return false
    }

    override suspend fun getStartPageToken(): Result<String> {
        return Result.success("")
    }

    override suspend fun listAllFiles(fields: Set<FileField>): Result<List<DriveFile>> {
        return Result.success(emptyList())
    }

    override suspend fun listChanges(pageToken: String): Result<ChangeList> {
        return Result.success(ChangeList(emptyList(), ""))
    }

    override suspend fun downloadFile(
        fileId: String,
        outputPath: Path,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit
    ): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun exportFile(
        fileId: String,
        exportMimeType: String,
        outputPath: Path
    ): Result<Unit> {
        return Result.success(Unit)
    }
}
