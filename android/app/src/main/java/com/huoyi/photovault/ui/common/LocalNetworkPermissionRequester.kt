package com.huoyi.photovault.ui.common

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.huoyi.photovault.R
import com.huoyi.photovault.ui.theme.LiquidDialogButton
import com.huoyi.photovault.ui.theme.LiquidDialogButtonStyle
import com.huoyi.photovault.ui.theme.LiquidGlassDialog
import com.huoyi.photovault.util.FileLogger
import com.huoyi.photovault.util.LocalNetworkPermission

/** Stable handle returned by [rememberLocalNetworkPermissionRequester]. */
@Stable
class LocalNetworkPermissionRequester internal constructor(
    private val requestImpl: State<(() -> Unit) -> Unit>
) {
    /**
     * Asks for [LocalNetworkPermission.PERMISSION] if it is still missing and hasn't been asked
     * on this screen yet, then runs [onFinished] once the system prompt is answered (or right
     * away when there is nothing to ask). [onFinished] runs whether or not it was granted.
     */
    fun request(onFinished: () -> Unit = {}) = requestImpl.value(onFinished)
}

/**
 * Android 17 local network permission request shared by the login and main screens.
 * When the user declines, shows a dialog explaining that LAN access is needed to reach the
 * server, with "Retry" (system prompt still available) or "Open settings" (permanently denied).
 * [onGranted] fires after a grant, including one made on the settings page.
 * No-op below API 37.
 */
@Composable
fun rememberLocalNetworkPermissionRequester(onGranted: () -> Unit = {}): LocalNetworkPermissionRequester {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    // Asked at most once per screen instance, so a declined user isn't re-prompted on every
    // tap; the dialog's Retry bypasses this.
    var asked by rememberSaveable { mutableStateOf(false) }
    var denial by rememberSaveable { mutableStateOf<LocalNetworkPermission.Denial?>(null) }
    var awaitingSettings by rememberSaveable { mutableStateOf(false) }
    // Not saved: after recreation the user simply taps again.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val rationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, LocalNetworkPermission.PERMISSION)
        } ?: false
        denial = LocalNetworkPermission.denialAfterRequest(granted, rationale)
        FileLogger.log("Permission", "ACCESS_LOCAL_NETWORK granted=$granted rationale=$rationale")
        if (granted) currentOnGranted()
        val next = pending
        pending = null
        next?.invoke()
    }

    // Returning from the app settings page: pick up a grant made there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val wasAwaiting = awaitingSettings
        awaitingSettings = false
        if ((denial != null || wasAwaiting) && LocalNetworkPermission.isGranted(context)) {
            denial = null
            currentOnGranted()
        }
    }

    denial?.let { current ->
        LiquidGlassDialog(
            onDismissRequest = { denial = null },
            title = stringResource(R.string.local_network_permission_title),
            text = stringResource(
                if (current == LocalNetworkPermission.Denial.PERMANENT) {
                    R.string.local_network_permission_denied_settings
                } else {
                    R.string.local_network_permission_denied
                }
            )
        ) {
            LiquidDialogButton(
                text = stringResource(R.string.action_later),
                onClick = { denial = null },
                style = LiquidDialogButtonStyle.Neutral
            )
            if (current == LocalNetworkPermission.Denial.PERMANENT) {
                LiquidDialogButton(
                    text = stringResource(R.string.action_open_settings),
                    onClick = {
                        denial = null
                        awaitingSettings = LocalNetworkPermission.openAppSettings(context)
                    },
                    style = LiquidDialogButtonStyle.Accent
                )
            } else {
                LiquidDialogButton(
                    text = stringResource(R.string.action_retry),
                    onClick = {
                        denial = null
                        launcher.launch(LocalNetworkPermission.PERMISSION)
                    },
                    style = LiquidDialogButtonStyle.Accent
                )
            }
        }
    }

    val requestImpl = rememberUpdatedState<(() -> Unit) -> Unit> { onFinished ->
        if (LocalNetworkPermission.isGranted(context) || asked) {
            onFinished()
        } else {
            asked = true
            pending = onFinished
            launcher.launch(LocalNetworkPermission.PERMISSION)
        }
    }
    return remember { LocalNetworkPermissionRequester(requestImpl) }
}
