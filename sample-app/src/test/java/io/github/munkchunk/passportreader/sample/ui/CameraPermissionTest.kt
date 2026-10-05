package io.github.munkchunk.passportreader.sample.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionTest {

    @Test
    fun `granted wins whatever Android says about asking again`() {
        assertEquals(CameraPermission.Granted, CameraPermission.afterRequest(granted = true, canAskAgain = false))
        assertEquals(CameraPermission.Granted, CameraPermission.afterRequest(granted = true, canAskAgain = true))
    }

    @Test
    fun `refused once is denied, so asking again still shows the dialog`() {
        assertEquals(CameraPermission.Denied, CameraPermission.afterRequest(granted = false, canAskAgain = true))
    }

    @Test
    fun `refused with no further dialog is blocked`() {
        assertEquals(CameraPermission.Blocked, CameraPermission.afterRequest(granted = false, canAskAgain = false))
    }
}
