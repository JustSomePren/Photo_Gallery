package com.justsomepren.gallery.data

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginMessageTest {

    private fun message(code: Int) = GoogleAuth.problem(ApiException(Status(code)))

    @Test
    fun wrongCloudSetupPointsToTheClientSettings() {
        assertTrue(message(CommonStatusCodes.DEVELOPER_ERROR).contains("SHA-1"))
    }

    @Test
    fun otherProblemsSayWhatToDo() {
        assertTrue(message(CommonStatusCodes.NETWORK_ERROR).contains("internetu"))
        assertTrue(message(CommonStatusCodes.CANCELED).contains("zrušeno"))
        assertTrue(message(CommonStatusCodes.INTERNAL_ERROR).contains("testovacími uživateli"))
    }

    @Test
    fun accountMissingOnThePhoneSaysWhereToAddIt() {
        assertTrue(message(CommonStatusCodes.INVALID_ACCOUNT).contains("Nastavení telefonu"))
    }

    @Test
    fun playServicesProblemsPointToTheStore() {
        assertTrue(message(CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED).contains("Play Store"))
        assertTrue(message(CommonStatusCodes.SERVICE_MISSING).contains("Play Store"))
    }

    @Test
    fun appMissingInGoogleCloudSaysSo() {
        val error = ApiException(Status(CommonStatusCodes.INTERNAL_ERROR, "Unknown error [status=UNREGISTERED_ON_API_CONSOLE]."))
        assertTrue(GoogleAuth.problem(error).contains("není zaregistrovaná v Google Cloud"))
    }
}
