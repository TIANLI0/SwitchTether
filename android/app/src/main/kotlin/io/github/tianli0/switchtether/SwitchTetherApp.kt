package io.github.tianli0.switchtether

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class SwitchTetherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        io.github.tianli0.switchtether.update.UpdateCheckWorker.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.onBackground()
        })
        // Presence observation survives reboots on most builds; re-arming is idempotent and cheap.
        if (container.companion.associations.value.isNotEmpty()) container.companion.observeAll()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) container.onTrimMemory()
    }
}
