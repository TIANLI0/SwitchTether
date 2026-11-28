package io.github.tianli0.switchtether

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import io.github.tianli0.switchtether.ui.PageRoot
import io.github.tianli0.switchtether.ui.nav.Route

/**
 * One detail page ([Route]) in its own activity, so the system draws opening it and the predictive
 * back gesture to the page underneath (ColorOS's own activity motion), exactly as for any
 * multi-activity app. Pages hold no back stack of their own: opening another page starts another
 * activity, back finishes this one.
 */
class PageActivity : AppActivity() {
    companion object {
        private const val EXTRA_ROUTE = "io.github.tianli0.switchtether.ROUTE"

        private var lastRoute: Route? = null
        private var lastAt = 0L

        fun intent(context: Context, route: Route): Intent =
            Intent(context, PageActivity::class.java).putExtra(EXTRA_ROUTE, Route.encode(route))

        /** Opens [route]; a double tap on the same row opens it once. */
        fun open(context: Context, route: Route) {
            val now = SystemClock.uptimeMillis()
            if (route == lastRoute && now - lastAt < DOUBLE_TAP_MS) return
            lastRoute = route
            lastAt = now
            val i = intent(context, route)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        private const val DOUBLE_TAP_MS = 600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = Route.decode(intent.getStringExtra(EXTRA_ROUTE))
        if (route == null) {
            finish()
            return
        }
        setAppContent { PageRoot(route) }
    }
}
