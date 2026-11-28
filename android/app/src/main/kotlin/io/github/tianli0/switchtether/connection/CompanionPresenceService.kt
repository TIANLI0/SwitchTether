package io.github.tianli0.switchtether.connection

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.tianli0.switchtether.SwitchTetherApp

/**
 * Bound by the system while a computer the user associated through [CompanionLink] is connected
 * over Bluetooth. Appearance may start the connectedDevice monitor service from the background
 * (REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND); disappearance lets the monitor
 * stop when the user chose "only while the computer is nearby".
 */
class CompanionPresenceService : CompanionDeviceService() {

    private fun container() = (application as? SwitchTetherApp)?.link

    private fun appeared(what: String) {
        Log.i(TAG, "computer nearby: $what")
        container()?.onCompanionPresence(true)
    }

    private fun disappeared(what: String) {
        Log.i(TAG, "computer gone: $what")
        container()?.onCompanionPresence(false)
    }

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared("association ${associationInfo.id}")

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared("association ${associationInfo.id}")

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED, DevicePresenceEvent.EVENT_BLE_APPEARED,
            -> appeared("event ${event.event} association ${event.associationId}")
            DevicePresenceEvent.EVENT_BT_DISCONNECTED, DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            -> disappeared("event ${event.event} association ${event.associationId}")
            else -> Log.i(TAG, "presence event ${event.event}")
        }
    }

    private companion object {
        const val TAG = "ST/Companion"
    }
}
