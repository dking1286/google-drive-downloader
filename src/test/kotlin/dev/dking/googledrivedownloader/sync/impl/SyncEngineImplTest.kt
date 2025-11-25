package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.api.impl.GoogleDriveClientImpl
import dev.dking.googledrivedownloader.sync.SyncEngineConfig
import dev.dking.googledrivedownloader.sync.SyncEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineImplTest {

    private val driveClient = GoogleDriveClientImpl()
    private val config = SyncEngineConfig(
        downloadDirectory = Path.of("/tmp/test"),
        exportFormats = emptyMap(),
        maxConcurrentDownloads = 4,
        deleteRemovedFiles = false
    )
    private val syncEngine = SyncEngineImpl(driveClient, config)

    @Test
    fun `initialSync returns completed event with zero values`() = runTest {
        val events = syncEngine.initialSync().toList()
        assertEquals(1, events.size)
        val event = events.first() as SyncEvent.Completed
        assertEquals(0, event.filesProcessed)
        assertEquals(0L, event.bytesDownloaded)
        assertEquals(0, event.failedFiles)
        assertEquals(Duration.ZERO, event.duration)
    }

    @Test
    fun `incrementalSync returns completed event with zero values`() = runTest {
        val events = syncEngine.incrementalSync().toList()
        assertEquals(1, events.size)
        val event = events.first() as SyncEvent.Completed
        assertEquals(0, event.filesProcessed)
        assertEquals(0L, event.bytesDownloaded)
        assertEquals(0, event.failedFiles)
        assertEquals(Duration.ZERO, event.duration)
    }

    @Test
    fun `resumeSync returns completed event with zero values`() = runTest {
        val events = syncEngine.resumeSync().toList()
        assertEquals(1, events.size)
        val event = events.first() as SyncEvent.Completed
        assertEquals(0, event.filesProcessed)
        assertEquals(0L, event.bytesDownloaded)
        assertEquals(0, event.failedFiles)
        assertEquals(Duration.ZERO, event.duration)
    }

    @Test
    fun `getSyncStatus returns empty status`() = runTest {
        val result = syncEngine.getSyncStatus()
        assertTrue(result.isSuccess)
        val status = result.getOrNull()
        assertEquals(null, status?.lastSyncTime)
        assertEquals(0, status?.filesTracked)
        assertEquals(0L, status?.totalSize)
        assertEquals(0, status?.pendingFiles)
        assertEquals(0, status?.failedFiles)
    }

    @Test
    fun `getFailedFiles returns empty list`() = runTest {
        val result = syncEngine.getFailedFiles()
        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.getOrNull())
    }
}
