package dev.dking.googledrivedownloader.api.impl

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleDriveClientImplTest {

    private val client = GoogleDriveClientImpl(DriveClientConfig())

    @Test
    fun `authenticate returns success`() = runTest {
        val result = client.authenticate()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `authenticate with force returns success`() = runTest {
        val result = client.authenticate(forceReauth = true)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `isAuthenticated returns false`() {
        val result = client.isAuthenticated()
        assertFalse(result)
    }

    @Test
    fun `getStartPageToken returns empty string`() = runTest {
        val result = client.getStartPageToken()
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull())
    }

    @Test
    fun `listAllFiles returns empty list`() = runTest {
        val result = client.listAllFiles(setOf(FileField.ID, FileField.NAME))
        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.getOrNull())
    }

    @Test
    fun `listChanges returns empty ChangeList`() = runTest {
        val result = client.listChanges("token123")
        assertTrue(result.isSuccess)
        val changeList = result.getOrNull()
        assertEquals(emptyList(), changeList?.changes)
        assertEquals("", changeList?.newStartPageToken)
    }

    @Test
    fun `downloadFile returns success`() = runTest {
        val result = client.downloadFile("fileId", Path.of("/tmp/test"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `downloadFile with progress callback returns success`() = runTest {
        val result = client.downloadFile("fileId", Path.of("/tmp/test")) { _, _ -> }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `exportFile returns success`() = runTest {
        val result = client.exportFile("fileId", "application/pdf", Path.of("/tmp/test.pdf"))
        assertTrue(result.isSuccess)
    }
}
