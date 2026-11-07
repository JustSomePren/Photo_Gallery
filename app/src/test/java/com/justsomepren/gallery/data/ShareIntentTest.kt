package com.justsomepren.gallery.data

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentTest {
    private fun item(id: Long, video: Boolean = false) = MediaItem(
        id, Uri.parse("content://media/external/images/media/$id"), if (video) "video/mp4" else "image/jpeg", video,
        1_000, 1_000, 1, "Camera", false, "PXL_$id.jpg", 1, 1, 1, "DCIM/Camera/",
    )

    @Test
    fun onePhotoIsSharedAsOneSoInstagramOffersStory() {
        val intent = GooglePhotos.shareOne(item(1))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        @Suppress("DEPRECATION")
        assertEquals(item(1).uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, intent.clipData!!.itemCount)
    }

    @Test
    fun severalPhotosStaySeveral() {
        val intent = GooglePhotos.shareIntent(listOf(item(1), item(2, video = true)))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(2, intent.clipData!!.itemCount)
    }
}
