package io.github.tianli0.switchtether.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Optional CompanionDeviceManager association with the user's computer over Bluetooth.
 *
 * The user picks the computer in the system's own association dialog. Once associated:
 *  - the system exempts SwitchTether from battery optimisation
 *    (REQUEST_COMPANION_RUN_IN_BACKGROUND), so the monitor connection is not cut by Doze;
 *  - [CompanionPresenceService] is bound when the computer connects / disconnects over Bluetooth,
 *    which may start the connectedDevice foreground service from the background
 *    (REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND);
 *  - with [nearbyOnly], the background connection runs only while the computer is nearby, so the
 *    process can be reclaimed completely when the user walks away (lower memory and battery).
 *
 * Nothing here touches the computer or any agent: it is a phone-side system association only.
 */
class CompanionLink(
    private val context: Context,
    /** Shared with the other process (the UI sets "nearby only", the `:link` process records presence). */
    private val prefs: io.github.tianli0.switchtether.data.LinkPrefs,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    data class Association(val id: Int?, val name: String, val address: String?)

    /** A computer this phone is bonded with over Bluetooth. */
    data class BondedComputer(val name: String, val address: String)

    interface Callback {
        fun onChooser(sender: IntentSender)
        fun onFailure(error: String?)
    }

    private val cdm: CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            runCatching { context.getSystemService(CompanionDeviceManager::class.java) }.getOrNull()
        } else {
            null
        }

    val isSupported: Boolean get() = cdm != null

    /** Presence observation needs API 31+. */
    val canObservePresence: Boolean get() = cdm != null && Build.VERSION.SDK_INT >= 31

    private val _associations = MutableStateFlow(load())
    val associations: StateFlow<List<Association>> = _associations.asStateFlow()

    /** Last presence reported by the system (true until the first "disappeared" event). */
    val present: StateFlow<Boolean> = prefs.companionPresent.stateIn(scope, SharingStarted.Eagerly, true)

    /** "Only stay connected while the computer is nearby" (meaningful only with an association). */
    val nearbyOnly: StateFlow<Boolean> = prefs.companionNearbyOnly.stateIn(scope, SharingStarted.Eagerly, false)

    /** Stores the choice, then runs [then] (on the main thread). */
    fun setNearbyOnly(value: Boolean, then: () -> Unit = {}) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            prefs.setCompanionNearbyOnly(value)
            then()
        }
    }

    suspend fun setPresent(value: Boolean) {
        prefs.setCompanionPresent(value)
    }

    /**
     * Whether a *background-only* connection (the "Stay connected in background" setting, without
     * pinned tasks or port forwards) should be kept right now. False only when the user bound a
     * computer, chose "nearby only" and the system reported the computer gone. Reads the stored
     * values (the other process may have just changed them).
     */
    suspend fun allowsBackgroundKeepAlive(): Boolean {
        refresh()
        return _associations.value.isEmpty() || !canObservePresence || !prefs.companionNearbyOnly.first() || prefs.companionPresent.first()
    }

    fun refresh() {
        _associations.value = load()
    }

    private fun load(): List<Association> {
        val m = cdm ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                m.myAssociations.map { a ->
                    Association(a.id, a.displayName?.toString() ?: a.deviceMacAddress?.toString() ?: "#${a.id}", a.deviceMacAddress?.toString())
                }
            } else {
                @Suppress("DEPRECATION")
                m.associations.map { Association(null, it, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot list associations: ${e.message}")
            emptyList()
        }
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Bonded Bluetooth computers that are not associated yet. The system chooser only scans for
     * discoverable devices; a bonded computer is offered only when asked for by its address.
     */
    @SuppressLint("MissingPermission") // checked by hasBluetoothPermission()
    fun bondedComputers(): List<BondedComputer> {
        if (cdm == null || !hasBluetoothPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val associated = _associations.value.mapNotNull { it.address?.uppercase() }.toSet()
        return try {
            adapter.bondedDevices.orEmpty()
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
                .filter { it.address.uppercase() !in associated }
                .map { BondedComputer(it.name?.takeIf { n -> n.isNotBlank() } ?: it.address, it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot list bonded devices: ${e.message}")
            emptyList()
        }
    }

    /** Starts the system association flow: the bonded device at [address], or (null) a scan. */
    fun associate(callback: Callback, address: String? = null) {
        val m = cdm ?: return callback.onFailure("unsupported")
        val filter = BluetoothDeviceFilter.Builder().apply { if (address != null) setAddress(address) }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(address != null)
            .build()
        val cb = object : CompanionDeviceManager.Callback() {
            @Deprecated("API 33 uses onAssociationPending")
            override fun onDeviceFound(intentSender: IntentSender) = callback.onChooser(intentSender)
            override fun onAssociationPending(intentSender: IntentSender) = callback.onChooser(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                refresh()
                observeAll()
            }

            override fun onFailure(error: CharSequence?) = callback.onFailure(error?.toString())
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                m.associate(request, context.mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                m.associate(request, cb, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            callback.onFailure(e.message)
        }
    }

    /** (Re)starts presence observation for every association; cheap and idempotent. */
    fun observeAll() {
        val m = cdm ?: return
        if (Build.VERSION.SDK_INT < 31) return
        for (a in _associations.value) {
            try {
                if (Build.VERSION.SDK_INT >= 36 && a.id != null) {
                    m.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(a.id).build())
                } else if (a.address != null) {
                    @Suppress("DEPRECATION")
                    m.startObservingDevicePresence(a.address)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot observe presence of ${a.name}: ${e.message}")
            }
        }
    }

    fun disassociate(a: Association) {
        val m = cdm ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33 && a.id != null) {
                m.disassociate(a.id)
            } else if (a.address != null) {
                @Suppress("DEPRECATION")
                m.disassociate(a.address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "disassociate failed: ${e.message}")
        }
        refresh()
        if (_associations.value.isEmpty()) scope.launch { setPresent(true) }
    }

    private companion object {
        const val TAG = "ST/Companion"
    }
}
