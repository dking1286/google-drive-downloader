package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.sync.FileRecord
import dev.dking.googledrivedownloader.sync.SyncEngine
import dev.dking.googledrivedownloader.sync.SyncEngineConfig
import dev.dking.googledrivedownloader.sync.SyncEvent
import dev.dking.googledrivedownloader.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Duration

/**
 * Boilerplate implementation of SyncEngine that returns empty/default values.
 * This is a placeholder for the actual implementation.
 */
class SyncEngineImpl(
    private val driveClient: GoogleDriveClient,
    private val config: SyncEngineConfig
) : SyncEngine {

    override fun initialSync(): Flow<SyncEvent> {
        return flowOf(SyncEvent.Completed(0, 0, 0, Duration.ZERO))
    }

    override fun incrementalSync(): Flow<SyncEvent> {
        return flowOf(SyncEvent.Completed(0, 0, 0, Duration.ZERO))
    }

    override fun resumeSync(): Flow<SyncEvent> {
        return flowOf(SyncEvent.Completed(0, 0, 0, Duration.ZERO))
    }

    override suspend fun getSyncStatus(): Result<SyncStatus> {
        return Result.success(SyncStatus(null, 0, 0, 0, 0))
    }

    override suspend fun getFailedFiles(): Result<List<FileRecord>> {
        return Result.success(emptyList())
    }
}
