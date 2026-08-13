package com.fraudguard.monitor.permission

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * requirements.md 34.5章: 権限リクエストフローが参照する許可状態判定ロジックの検証。
 * RobolectricのShadowApplicationで許可状態をシミュレートする。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RequiredPermissionsTest {

    @Test
    fun `ungranted permissions are reported as missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val missing = RequiredPermissions.missingRuntimePermissions(context)

        assertTrue(Manifest.permission.READ_PHONE_STATE in missing)
        assertTrue(Manifest.permission.RECEIVE_SMS in missing)
    }

    @Test
    fun `granting a permission removes it from the missing list`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val missing = RequiredPermissions.missingRuntimePermissions(context)

        assertFalse(Manifest.permission.READ_PHONE_STATE in missing)
        assertTrue(Manifest.permission.RECEIVE_SMS in missing, "他の未許可権限には影響しないこと")
    }

    @Test
    fun `granting every runtime permission results in an empty missing list`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(*RequiredPermissions.runtimePermissions().toTypedArray())

        assertEquals(emptyList(), RequiredPermissions.missingRuntimePermissions(context))
    }

    @Test
    fun `every runtime permission has a non-blank user-facing label`() {
        RequiredPermissions.runtimePermissions().forEach { permission ->
            assertTrue(RequiredPermissions.label(permission).isNotBlank(), "label missing for $permission")
        }
    }
}
