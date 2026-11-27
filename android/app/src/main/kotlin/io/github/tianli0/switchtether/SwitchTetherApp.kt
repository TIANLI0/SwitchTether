package io.github.tianli0.switchtether

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import io.github.tianli0.switchtether.link.LinkContainer

/**
 * Two processes (docs/ANDROID_UI.md §38): the UI process (activities, screens, transfers) builds
 * [container]; the small `:link` process (background connection, notifications, Live Updates,
 * widget, port forwards, monitor service) builds [link]. Each process builds only its own, so
 * code in one process must not touch the other's property.
 */
class SwitchTetherApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    /** Set only in the `:link` process. */
    var link: LinkContainer? = null
        private set

    val isLinkProcess: Boolean by lazy { getProcessName().endsWith(LINK_SUFFIX) }

    // WorkManager runs its jobs in the UI process only; the `:link` process may still enqueue work.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setDefaultProcessName(packageName).build()

    override fun onCreate() {
        super.onCreate()
        if (isLinkProcess) {
            val l = LinkContainer(this)
            link = l
            // Presence observation survives reboots on most builds; re-arming is idempotent and cheap.
            if (l.companion.associations.value.isNotEmpty()) l.companion.observeAll()
            return
        }
        container = AppContainer(this)
        io.github.tianli0.switchtether.update.UpdateCheckWorker.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.onBackground()
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_UI_HIDDEN) return
        link?.onTrimMemory()
        if (!isLinkProcess) container.onTrimMemory()
    }

    companion object {
        const val LINK_SUFFIX = ":link"
    }
}
