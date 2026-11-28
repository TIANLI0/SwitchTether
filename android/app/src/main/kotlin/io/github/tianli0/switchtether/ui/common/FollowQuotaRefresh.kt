package io.github.tianli0.switchtether.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.tianli0.switchtether.ui.appContainer

@Composable
internal fun FollowQuotaRefresh() {
    val repo = appContainer().management
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(repo, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { repo.followQuotaRefresh() }
    }
}
