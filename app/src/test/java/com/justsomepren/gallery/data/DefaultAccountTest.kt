package com.justsomepren.gallery.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAccountTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test
    fun firstAccountUntilAnotherIsChosen() {
        assertNull(prefs.mainAccount)
        prefs.addAccount("osobni@gmail.com")
        prefs.addAccount("spolecny@gmail.com")
        assertEquals("osobni@gmail.com", prefs.mainAccount)

        prefs.setDefaultAccount("spolecny@gmail.com")
        assertEquals(listOf("spolecny@gmail.com", "osobni@gmail.com"), prefs.accountsDefaultFirst)
        // Kept after a restart of the app
        assertEquals("spolecny@gmail.com", Prefs(context).mainAccount)
    }

    @Test
    fun removedDefaultFallsBackToTheFirstAccount() {
        prefs.addAccount("osobni@gmail.com")
        prefs.addAccount("spolecny@gmail.com")
        prefs.setDefaultAccount("spolecny@gmail.com")
        prefs.removeAccount("spolecny@gmail.com")
        assertEquals("osobni@gmail.com", prefs.mainAccount)
    }
}
