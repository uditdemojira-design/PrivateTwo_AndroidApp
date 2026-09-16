package org.privatetwo.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.privatetwo.app.core.crypto.CryptoEngine

class FileTransferManagerTest {

    @Test
    fun testFileNameSanitizationPreventsPathTraversal() {
        val malicious1 = "../../../etc/passwd"
        val safe1 = FileTransferManager.sanitizeFileName(malicious1)
        assertFalse("Must not contain ../", safe1.contains(".."))
        assertFalse("Must not contain /", safe1.contains("/"))
        assertEquals("passwd", safe1)

        val malicious2 = "..\\..\\Windows\\System32\\cmd.exe"
        val safe2 = FileTransferManager.sanitizeFileName(malicious2)
        assertFalse("Must not contain ..", safe2.contains(".."))
        assertFalse("Must not contain \\", safe2.contains("\\"))
        assertEquals("cmd.exe", safe2)

        val malicious3 = "photo\u0000file.png"
        val safe3 = FileTransferManager.sanitizeFileName(malicious3)
        assertEquals("photo_file.png", safe3)

        val malicious4 = "..."
        val safe4 = FileTransferManager.sanitizeFileName(malicious4)
        assertTrue(safe4.startsWith("file_"))
    }

    @Test
    fun testFileChunkingAndChecksumCalculation() {
        val dummyData = ByteArray(50 * 1024) { (it % 256).toByte() }
        val originalChecksum = CryptoEngine.computeSha256(dummyData)

        val chunkSize = FileTransferManager.CHUNK_SIZE_BYTES
        val totalChunks = (dummyData.size + chunkSize - 1) / chunkSize
        assertEquals(4, totalChunks)

        val reassembled = ByteArray(dummyData.size)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = (start + chunkSize).coerceAtMost(dummyData.size)
            val chunk = dummyData.copyOfRange(start, end)
            System.arraycopy(chunk, 0, reassembled, start, chunk.size)
        }

        val reassembledChecksum = CryptoEngine.computeSha256(reassembled)
        assertEquals("Reassembled file must have identical SHA-256 checksum", originalChecksum, reassembledChecksum)
    }

    @Test
    fun testCorruptedChunkCausesChecksumMismatch() {
        val original = "Original uncorrupted document content".toByteArray(Charsets.UTF_8)
        val originalChecksum = CryptoEngine.computeSha256(original)

        val corrupted = original.clone()
        corrupted[5] = (corrupted[5].toInt() xor 0xFF).toByte()
        val corruptedChecksum = CryptoEngine.computeSha256(corrupted)

        assertFalse("Checksums must differ when chunk is corrupted", originalChecksum.equals(corruptedChecksum, ignoreCase = true))
    }
}
