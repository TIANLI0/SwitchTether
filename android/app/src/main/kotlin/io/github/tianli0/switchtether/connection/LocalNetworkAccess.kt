package io.github.tianli0.switchtether.connection

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 17 local network protection: apps targeting API 37 need the runtime permission
 * `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group) for TCP to LAN addresses and for NSD/mDNS.
 * Without it, LAN connections silently time out; relay and Tailscale (CGNAT / DNS) still work.
 */
class LocalNetworkAccess(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_network", Context.MODE_PRIVATE)

    /** True on Android 17+, where the permission exists and is enforced for this app. */
    val required: Boolean get() = Build.VERSION.SDK_INT >= ANDROID_17

    private val _granted = MutableStateFlow(computeGranted())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private fun computeGranted(): Boolean =
        !required || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Re-reads the permission (after the dialog, or when coming back from system settings). */
    fun refresh(): Boolean = computeGranted().also { _granted.value = it }

    fun isGranted(): Boolean = refresh()

    /** Whether the rationale was already shown once (the first LAN connect asks only once by itself). */
    var rationaleShown: Boolean
        get() = prefs.getBoolean("rationale_shown", false)
        set(v) = prefs.edit().putBoolean("rationale_shown", v).apply()

    /** The system will not show its dialog again (permanently denied): send the user to settings. */
    fun permanentlyDenied(activity: Activity): Boolean =
        required && !isGranted() && rationaleShown && !activity.shouldShowRequestPermissionRationale(PERMISSION)

    companion object {
        /** `Build.VERSION_CODES` name for 37 is not used to keep compiling against older platforms. */
        const val ANDROID_17 = 37
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
