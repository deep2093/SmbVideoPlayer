package com.deepak.videolibrary.player

import com.deepak.videolibrary.smb.SmbErrors
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SmbErrorsTest {
    @Test fun permanentOrCancelledCausesAreNotRetried() {
        assertFalse(
            SmbErrors.isTransient(
                IOException(
                    com.deepak.videolibrary.smb
                        .SmbSecurityException(),
                ),
            ),
        )
        assertFalse(SmbErrors.isTransient(IOException(IllegalArgumentException("Destination already exists"))))
        assertFalse(SmbErrors.isTransient(java.io.InterruptedIOException("cancelled")))
        assertTrue(SmbErrors.isTransient(java.net.SocketTimeoutException("timeout")))
    }

    @Test fun explainsKnownValidationWithoutExposingArbitraryMessages() {
        assertTrue(SmbErrors.describe(IllegalArgumentException("Destination already exists")).contains("already exists"))
        assertTrue(
            SmbErrors
                .describe(
                    IOException(IllegalArgumentException("Source and destination are the same")),
                ).contains("different destination"),
        )
        val secret = "smb://person:secret-password@private-server/video.mp4"
        assertFalse(SmbErrors.describe(IllegalArgumentException(secret)).contains(secret))
        assertFalse(SmbErrors.describe(IOException(secret)).contains("secret-password"))
    }
    @org.junit.Test fun missingFileIsPermanentAndDoesNotExposeItsPath() {
        val error=java.io.FileNotFoundException("secret/path/movie.mp4")
        org.junit.Assert.assertFalse(com.deepak.videolibrary.smb.SmbErrors.isTransient(error))
        org.junit.Assert.assertFalse(com.deepak.videolibrary.smb.SmbErrors.describe(error).contains("secret"))
    }

}
