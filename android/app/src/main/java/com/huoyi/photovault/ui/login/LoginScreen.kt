package com.huoyi.photovault.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.huoyi.photovault.ui.theme.LocalPhotoVaultDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.huoyi.photovault.R
import com.huoyi.photovault.ui.common.rememberLocalNetworkPermissionRequester
import com.huoyi.photovault.ui.theme.GlassBar
import com.huoyi.photovault.ui.theme.LocalGlassBackdrop
import com.huoyi.photovault.ui.theme.SemanticTone
import com.huoyi.photovault.ui.theme.photoVaultFocusRing
import com.huoyi.photovault.ui.theme.semanticColorSet
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Login screen.
 *
 * The credentials card is centered in the space inside the system bars and always
 * keeps its natural size. When the keyboard opens, the whole card (glass and
 * content) moves up with it so its bottom edge always sits 16dp above the keyboard,
 * whichever field is focused (see [loginCardShiftPx]). The top of the card may go
 * under the status bar meanwhile. Keyboard hidden, nothing moves.
 *
 * Behind everything, a wall of photographs drifts continuously: three columns of
 * prints scrolling at different speeds and directions, tilted a few degrees. It
 * establishes what the app is for without a word of marketing copy, and it gives
 * the liquid-glass form real texture to blur and refract as the pictures pass
 * behind it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    LoginScreenContent(onLoginSuccess, viewModel, null, null, true)
}

private val LocalLoginForegroundVisible = staticCompositionLocalOf { true }

@Composable
internal fun LoginScreenForRenderingTest(
    renderingState: LoginUiState,
    backgroundFixtureColor: Color?,
    renderForeground: Boolean = true,
    onLoginSuccess: () -> Unit = {}
) {
    LoginScreenContent(onLoginSuccess, null, renderingState, backgroundFixtureColor, renderForeground)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginScreenContent(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel?,
    renderingState: LoginUiState?,
    backgroundFixtureColor: Color?,
    renderForeground: Boolean
) {
    val observedState = viewModel?.uiState?.collectAsState()
    val uiState = renderingState ?: observedState?.value ?: LoginUiState()
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val editableTouchMarker = remember { EditableTouchMarker() }
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    val dismissKeyboard: State<() -> Unit> = rememberUpdatedState {
        focusManager.clearFocus()
        keyboardController?.hide()
        Unit
    }

    // QR scanner: the web console's server QR carries both addresses (server QR protocol
    // v1, see ServerQrCode and protocol/README.md); legacy single-URL codes are still
    // accepted. The result goes only through LoginViewModel.onQrScanned.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.takeIf { it.isNotEmpty() }?.let { viewModel?.onQrScanned(it) }
    }
    val launchScanner = {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("将 Web 端的服务器二维码放入取景框")
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            }
        )
    }
    // Android 17 blocks LAN connections until ACCESS_LOCAL_NETWORK is granted. Ask on the
    // user's first connect attempt (login / test connection); the action continues either
    // way, because the public address works without it.
    val localNetworkPermission = rememberLocalNetworkPermissionRequester()

    var navigationDispatched by remember { mutableStateOf(false) }
    // Both auto-login and interactive login converge on one navigation effect.
    LaunchedEffect(uiState.hasValidToken, uiState.loginSuccess) {
        if (!navigationDispatched && (uiState.hasValidToken || uiState.loginSuccess)) {
            navigationDispatched = true
            onLoginSuccess()
        }
    }

    // The connection-test outcome is shown inline (status pill next to the server
    // section) rather than as a snackbar, so it stays visually attached to the
    // field it describes. Clear it after a beat so it can't read as stale truth.
    LaunchedEffect(uiState.publicTestResult, uiState.lanTestResult) {
        if (uiState.publicTestResult != null || uiState.lanTestResult != null) {
            delay(5000)
            viewModel?.clearTestResult()
        }
    }

    // Pill summary over every tested address: one reachable address is enough to
    // log in, so any success → Success; only all-failed → Failure. Per-address
    // outcomes are shown by the pulsing tint on each field (testResultPulse).
    val testResults = listOfNotNull(uiState.publicTestResult, uiState.lanTestResult)
    val testSummary: TestConnectionResult? = when {
        testResults.any { it is TestConnectionResult.Success } -> TestConnectionResult.Success
        else -> testResults.firstOrNull { it is TestConnectionResult.Failure }
    }

    val dark = LocalPhotoVaultDarkTheme.current

    // Backdrop captured for the glass form to sample. It holds the photo wall and
    // its scrim, never the form itself — sampling a layer that contains the glass
    // would feed back on itself.
    val bgBackdrop = rememberLayerBackdrop()

    // Keyboard-driven card shift. The IME inset is read only inside the card's
    // offset lambda (placement), so it never changes measurement or recomposes.
    val imeInsets = WindowInsets.ime
    // Un-shifted card bottom and root height in window px, from the slot around the
    // offset card.
    var cardBottomPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }

    CompositionLocalProvider(LocalLoginForegroundVisible provides renderForeground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dismissKeyboardOnOutsideTap(
                    marker = editableTouchMarker,
                    imeVisible = imeVisible,
                    onDismiss = dismissKeyboard
                )
        ) {
        LoginPhotoBackdrop(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(bgBackdrop),
            fixtureColor = backgroundFixtureColor
        )

        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 24.dp, top = 64.dp)
                .testTag("login_title"),
            color = if (LocalLoginForegroundVisible.current) Color.White else Color.Transparent,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            maxLines = 1
        )

        CompositionLocalProvider(LocalGlassBackdrop provides bgBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // System bars only. The IME is deliberately not applied here: it
                    // never changes measurement and is handled only as the card offset.
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Natural height even if taller than the space (landscape): never squashed.
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        // Un-shifted card bounds: the offset below moves only the child,
                        // so this never depends on the shift (no layout feedback loop).
                        .onGloballyPositioned { coords ->
                            cardBottomPx =
                                coords.positionInWindow().y.roundToInt() + coords.size.height
                            rootHeightPx = coords.findRootCoordinates().size.height
                        }
                ) {
                    // Credentials pane — real frosted glass that refracts + blurs the
                    // moving photo wall (falls back to a translucent card pre-API 31).
                    GlassBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Whole card (glass + content) rides up with the keyboard. Layout
                            // placement, not graphicsLayer: drawBackdrop re-samples the wall at
                            // the card's real position via onGloballyPositioned/localPositionOf.
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = -loginCardShiftPx(
                                        cardBottomPx = cardBottomPx,
                                        rootHeightPx = rootHeightPx,
                                        imeBottomPx = imeInsets.getBottom(this),
                                        marginPx = LoginCardKeyboardMargin.roundToPx()
                                    )
                                )
                            },
                        shape = RoundedCornerShape(30.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            // Section header: what this pane is for + live connection state.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "连接你的服务器",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (LocalLoginForegroundVisible.current) Color.White else Color.Transparent
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                ConnectionStatusPill(
                                    isTesting = uiState.isTesting,
                                    testResult = testSummary,
                                    onTest = { localNetworkPermission.request { viewModel?.testConnection() } },
                                    enabled = !uiState.isLoading && !uiState.isTesting
                                )
                            }

                            // Form-level note: the two address fields are peers (at least
                            // one required). The QR shortcut lives on the public field but
                            // fills both fields at once (ServerQrCode, protocol/README.md).
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.login_address_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Banner is for login failures only. Connection-test results
                            // are already shown by the status pill and under each address
                            // field, so a test failure does not raise the banner.
                            val notice = uiState.errorMessage
                            // Retained so the text stays put while the banner animates out.
                            var noticeText by remember { mutableStateOf("") }
                            LaunchedEffect(notice) {
                                if (notice != null) noticeText = notice
                            }

                            AnimatedVisibility(
                                visible = notice != null,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = noticeText,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }

                            val fieldColors = glassFieldColors(dark)

                            // Public address — the one field users can't guess, so it
                            // carries the QR shortcut as a trailing action.
                            OutlinedTextField(
                                value = uiState.serverAddress,
                                onValueChange = { viewModel?.onServerAddressChange(it) },
                                placeholder = { Text(stringResource(R.string.login_public_address_hint)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testResultPulse(uiState.publicTestResult, RoundedCornerShape(16.dp))
                                    .testTag("login_public_address")
                                    .markEditableTouchTarget(editableTouchMarker),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors,
                                isError = uiState.serverAddressError != null,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Dns,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).testTag("login_public_address_leading_icon")
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = launchScanner,
                                        enabled = !uiState.isLoading
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.QrCodeScanner,
                                            contentDescription = "扫描服务器二维码",
                                            modifier = Modifier.testTag("login_public_address_trailing_icon"),
                                            tint = if (LocalLoginForegroundVisible.current) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                    }
                                },
                                supportingText = addressErrorText(uiState.serverAddressError),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                enabled = !uiState.isLoading
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // LAN address: peer of the public address (at least one required).
                            OutlinedTextField(
                                value = uiState.lanAddress,
                                onValueChange = { viewModel?.onLanAddressChange(it) },
                                placeholder = { Text(stringResource(R.string.login_lan_address_hint)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testResultPulse(uiState.lanTestResult, RoundedCornerShape(16.dp))
                                    .testTag("login_lan_address")
                                    .markEditableTouchTarget(editableTouchMarker),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors,
                                isError = uiState.lanAddressError != null,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Lan,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).testTag("login_lan_address_leading_icon")
                                    )
                                },
                                supportingText = addressErrorText(uiState.lanAddressError),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                enabled = !uiState.isLoading
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = uiState.username,
                                onValueChange = { viewModel?.onUsernameChange(it) },
                                placeholder = { Text("用户名") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_username")
                                    .markEditableTouchTarget(editableTouchMarker),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors,
                                isError = uiState.usernameError != null,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).testTag("login_username_leading_icon")
                                    )
                                },
                                supportingText = uiState.usernameError?.let { error ->
                                    { Text(error) }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                enabled = !uiState.isLoading
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = uiState.password,
                                onValueChange = { viewModel?.onPasswordChange(it) },
                                placeholder = { Text("密码") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_password")
                                    .markEditableTouchTarget(editableTouchMarker),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors,
                                isError = uiState.passwordError != null,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).testTag("login_password_leading_icon")
                                    )
                                },
                                supportingText = uiState.passwordError?.let { error ->
                                    { Text(error) }
                                },
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = if (passwordVisible) {
                                                "隐藏密码"
                                            } else {
                                                "显示密码"
                                            },
                                            modifier = Modifier.testTag("login_password_trailing_icon"),
                                            tint = if (LocalLoginForegroundVisible.current) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        localNetworkPermission.request { viewModel?.login() }
                                    }
                                ),
                                enabled = !uiState.isLoading
                            )

                            // Whole row toggles, so the label is part of the hit target.
                            val rememberPasswordShape = RoundedCornerShape(14.dp)
                            val rememberPasswordInteraction = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .photoVaultFocusRing(rememberPasswordShape, rememberPasswordInteraction)
                                    .clip(rememberPasswordShape)
                                    .clickable(
                                        enabled = !uiState.isLoading,
                                        role = Role.Switch,
                                        interactionSource = rememberPasswordInteraction,
                                        indication = null
                                    ) {
                                        viewModel?.onRememberPasswordChange(!uiState.rememberPassword)
                                    }
                                    .focusable(
                                        enabled = !uiState.isLoading,
                                        interactionSource = rememberPasswordInteraction
                                    )
                                    .testTag("login_remember_password")
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "记住密码",
                                    modifier = Modifier.weight(1f).testTag("login_remember_password_label"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (LocalLoginForegroundVisible.current) Color.White else Color.Transparent
                                )
                                Switch(
                                    checked = uiState.rememberPassword,
                                    onCheckedChange = { viewModel?.onRememberPasswordChange(it) },
                                    enabled = !uiState.isLoading,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            PrimaryActionButton(
                                label = if (uiState.isLoading) "正在连接…" else "登录",
                                loading = uiState.isLoading,
                                enabled = !uiState.isLoading && !uiState.isTesting,
                                dark = dark,
                                onClick = { localNetworkPermission.request { viewModel?.login() } }
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private class EditableTouchMarker {
    var downHitEditable = false
}

private fun Modifier.markEditableTouchTarget(marker: EditableTouchMarker): Modifier =
    pointerInput(marker) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.changedToDownIgnoreConsumed() }) {
                    // The root resets first on Initial; descendants then identify field hits.
                    marker.downHitEditable = true
                }
            }
        }
    }

private fun Modifier.dismissKeyboardOnOutsideTap(
    marker: EditableTouchMarker,
    imeVisible: State<Boolean>,
    onDismiss: State<() -> Unit>
): Modifier = pointerInput(marker) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        marker.downHitEditable = false
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        var isTap = true
        var finished = false

        while (!finished) {
            // Final observes child controls after their Main-pass handling and consumes nothing.
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.any { it.id != down.id }) {
                isTap = false
            }

            val primary = event.changes.firstOrNull { it.id == down.id }
            if (primary == null) {
                isTap = false
                finished = true
            } else {
                if ((primary.position - down.position).getDistance() > touchSlop) {
                    isTap = false
                }
                when {
                    primary.changedToUpIgnoreConsumed() -> {
                        // Dismiss on a completed tap, never on pointer-down or cancellation.
                        if (event.type != PointerEventType.Release) isTap = false
                        finished = true
                    }
                    !primary.pressed -> {
                        isTap = false
                        finished = true
                    }
                }
            }
        }

        if (isTap && !marker.downHitEditable && imeVisible.value) {
            onDismiss.value()
        }
    }
}

// ---------------------------------------------------------------------------
// Scrolling photo wall
// ---------------------------------------------------------------------------

internal val LightLoginScrimAlphas = listOf(0.72f, 0.64f, 0.58f, 0.72f)
internal val DarkLoginScrimAlphas = listOf(0.90f, 0.68f, 0.68f, 0.94f)

/** Production login background, with injectable endpoint fixtures for pixel tests. */
@Composable
internal fun LoginPhotoBackdrop(
    modifier: Modifier = Modifier,
    fixtureColor: Color? = null
) {
    val dark = LocalPhotoVaultDarkTheme.current
    val scrimBrush = remember(dark) { photoWallScrim(dark) }
    val scrimTint = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.09f else 0.06f)
    val baseColor = if (dark) Color(0xFF120A0C) else Color(0xFFFFF3E6)
    Box(
        modifier = modifier
            .background(fixtureColor ?: baseColor)
            .drawWithContent {
                drawContent()
                drawRect(scrimBrush)
                drawRect(scrimTint)
            }
            .testTag("login_photo_backdrop")
    ) {
        if (fixtureColor == null) {
            ScrollingPhotoWall(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Twelve photographs in the public domain, by photographers who defined the
 * medium. Bundled (rather than fetched) because the wall has to be there before
 * the user has a server to talk to.
 *
 * All twelve are public domain in the United States — either published before
 * 1929, or made for the U.S. government (FSA / OWI / NPS) and therefore free of
 * copyright. Each was retrieved from Wikimedia Commons or museum open access
 * collections, and centre-cropped to a uniform 3:4 tile.
 *
 *  01 Dorothea Lange — Migrant Mother, 1936 (FSA)
 *  02 Ansel Adams — The Tetons and the Snake River, 1942 (NPS/NARA)
 *  03 Gordon Parks — American Gothic, Washington D.C., 1942 (FSA/OWI)
 *  04 Walker Evans — Penny Picture Display, Savannah, 1936 (FSA)
 *  05 Alfred Stieglitz — The Steerage, 1907
 *  06 Eugène Atget — Boulevard de Strasbourg, Paris, 1912
 *  07 Julia Margaret Cameron — Ophelia Study No. 2, 1867
 *  08 Carleton Watkins — Cathedral Rock, Yosemite, 1861
 *  09 Eadweard Muybridge — The Horse in Motion, 1878
 *  10 Lewis Hine — Spinner in Globe Cotton Mill, Augusta, 1909
 *  11 Marion Post Wolcott — Okeechobee migratory labor camp, 1941 (FSA)
 *  12 Jack Delano — Corwith rail yard, Chicago, 1943 (FSA/OWI Kodachrome)
 */
private val masterworkIds = listOf(
    R.drawable.masterwork_01,
    R.drawable.masterwork_02,
    R.drawable.masterwork_03,
    R.drawable.masterwork_04,
    R.drawable.masterwork_05,
    R.drawable.masterwork_06,
    R.drawable.masterwork_07,
    R.drawable.masterwork_08,
    R.drawable.masterwork_09,
    R.drawable.masterwork_10,
    R.drawable.masterwork_11,
    R.drawable.masterwork_12
)

/**
 * A single wall of prints moving upward forever.
 *
 * The old implementation animated each column independently. Apart from making
 * neighbouring columns move in opposite directions, every column was translated
 * inside a viewport-height graphics layer while its duplicated content extended
 * beyond that layer. On device, moving the recorded layer exposed areas that had
 * never been drawn — the large blank regions visible in the screenshot.
 *
 * This version draws an infinite grid directly into a viewport-sized [Canvas].
 * One shared [travel] value moves every row upward. Visible row coordinates and
 * photo indices are calculated with modular arithmetic, so no oversized child,
 * overflow, or duplicated backing layer is involved.
 */
@Composable
private fun ScrollingPhotoWall(modifier: Modifier = Modifier) {
    // imageResource is synchronous and resource-cached, so all tiles exist before
    // the first frame; nothing can pop in later as an async image request finishes.
    val prints = masterworkIds.map { ImageBitmap.imageResource(it) }
    val transition = rememberInfiniteTransition(label = "photoWall")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // One complete 12-row pattern every 72 seconds. Because the whole
            // wall shares this clock, no column can drift or restart separately.
            animation = tween(durationMillis = 72_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "photoWallOffset"
    )

    Canvas(modifier = modifier) {
        val columns = 4
        val gap = 4.dp.toPx()
        val tileWidth = (size.width - gap * (columns - 1)) / columns
        val tileHeight = tileWidth * 4f / 3f
        val rowPitch = tileHeight + gap

        // Twelve pattern rows make one exact cycle. Advancing by 5 (coprime to
        // 12) and offsetting columns by 3 uses all works without adjacent repeats.
        val rowsPerCycle = prints.size
        val cycleHeight = rowPitch * rowsPerCycle
        val travel = progress * cycleHeight

        // Rotation needs source pixels a little above and below the viewport.
        // Draw only those rows rather than constructing an off-screen tall layout.
        val overscan = size.maxDimension * 0.36f
        val firstRow = kotlin.math.floor((-overscan + travel) / rowPitch).toInt()
        val lastRow = kotlin.math.ceil((size.height + overscan + travel) / rowPitch).toInt()

        withTransform({
            rotate(degrees = -8f, pivot = center)
            scale(scaleX = 1.34f, scaleY = 1.34f, pivot = center)
        }) {
            for (absoluteRow in firstRow..lastRow) {
                val y = absoluteRow * rowPitch - travel
                val patternRow = Math.floorMod(absoluteRow, rowsPerCycle)

                repeat(columns) { column ->
                    val printIndex = (patternRow * 5 + column * 3) % prints.size
                    drawImage(
                        image = prints[printIndex],
                        dstOffset = IntOffset(
                            x = (column * (tileWidth + gap)).roundToInt(),
                            y = y.roundToInt()
                        ),
                        dstSize = IntSize(
                            width = tileWidth.roundToInt(),
                            height = tileHeight.roundToInt()
                        ),
                        filterQuality = FilterQuality.Medium
                    )
                }
            }
        }
    }
}

/**
 * Wash over the photo wall. Heaviest at the top (where the wordmark sits) and at
 * the bottom, easing off through the middle so the pictures still come through
 * behind the glass.
 */
internal fun photoWallScrim(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(
        0.00f to Color(0xFF120A0C).copy(alpha = DarkLoginScrimAlphas[0]),
        0.20f to Color(0xFF120A0C).copy(alpha = DarkLoginScrimAlphas[1]),
        0.58f to Color(0xFF120A0C).copy(alpha = DarkLoginScrimAlphas[2]),
        1.00f to Color(0xFF120A0C).copy(alpha = DarkLoginScrimAlphas[3])
    )
} else {
    Brush.verticalGradient(
        0.00f to Color.Black.copy(alpha = LightLoginScrimAlphas[0]),
        0.20f to Color.Black.copy(alpha = LightLoginScrimAlphas[1]),
        0.58f to Color.Black.copy(alpha = LightLoginScrimAlphas[2]),
        1.00f to Color.Black.copy(alpha = LightLoginScrimAlphas[3])
    )
}

// ---------------------------------------------------------------------------
// Form pieces
// ---------------------------------------------------------------------------

/**
 * Supporting slot for an address field: validation errors only. Returns null when
 * there is no error, so the field reserves no supporting-text space. Connection-test
 * outcomes are shown inside the field by [testResultPulse], not as a text line.
 */
private fun addressErrorText(error: String?): (@Composable () -> Unit)? =
    error?.let { message -> { Text(text = message, color = MaterialTheme.colorScheme.error) } }

/**
 * Breathing tint behind an address field for its connection-test result: green on
 * success, red on failure. It lasts exactly as long as the result is set, so it
 * stops when the 测试连接 pill resets (the screen clears results after 5 s).
 *
 * The field's container is translucent glass, so a tint drawn behind it shows
 * through. The animated alpha is read only in the draw phase, so pulsing redraws
 * the field without recomposing the form. No transition exists while idle.
 */
@Composable
private fun Modifier.testResultPulse(result: TestConnectionResult?, shape: Shape): Modifier {
    val tint = when (result) {
        is TestConnectionResult.Success -> semanticColorSet(SemanticTone.Success).accent
        is TestConnectionResult.Failure -> semanticColorSet(SemanticTone.Danger).accent
        null -> return this
    }
    val pulse = rememberInfiniteTransition(label = "testResultPulse")
    val alpha = pulse.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "testResultPulseAlpha"
    )
    return this.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, color = tint.copy(alpha = alpha.value))
    }
}

/** Label, high-contrast colours, and glyph for one [ConnectionStatusPill] state. */
private class PillLook(
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector?
)

/**
 * Tap-to-test pill that doubles as the connection status readout. Collapsing the
 * action and its result into one control keeps 登录 the only full-width button.
 */
@Composable
internal fun ConnectionStatusPill(
    isTesting: Boolean,
    testResult: TestConnectionResult?,
    onTest: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val look = when {
        isTesting -> PillLook("测试中", scheme.primary, scheme.onPrimary, null)
        testResult is TestConnectionResult.Success -> semanticColorSet(SemanticTone.Success).let {
            PillLook(stringResource(R.string.connection_status_success), it.container, it.foreground, Icons.Rounded.CheckCircle)
        }
        testResult is TestConnectionResult.Failure -> semanticColorSet(SemanticTone.Danger).let {
            PillLook(stringResource(R.string.connection_status_failed), it.container, it.foreground, Icons.Rounded.ErrorOutline)
        }
        else -> PillLook("测试连接", scheme.primary, scheme.onPrimary, null)
    }
    val stateTag = when {
        isTesting -> "testing"
        testResult is TestConnectionResult.Success -> "success"
        testResult is TestConnectionResult.Failure -> "danger"
        else -> "idle"
    }
    val shape = CircleShape
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Row(
        modifier = modifier
            .shadow(elevation = 4.dp, shape = shape, clip = false)
            .photoVaultFocusRing(shape, interactionSource)
            .clip(shape)
            .background(look.containerColor)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = indication,
                onClick = onTest
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .testTag("login_connection_status")
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = look.icon
        if (isTesting) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp).testTag("login_connection_status_${stateTag}_progress"),
                strokeWidth = 1.5.dp,
                color = if (LocalLoginForegroundVisible.current) look.contentColor else Color.Transparent
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp).testTag("login_connection_status_${stateTag}_icon"),
                tint = if (LocalLoginForegroundVisible.current) look.contentColor else Color.Transparent
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = look.label,
            modifier = Modifier.testTag("login_connection_status_${stateTag}_label"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (LocalLoginForegroundVisible.current) look.contentColor else Color.Transparent,
            maxLines = 1
        )
    }
}

/**
 * The single primary action: a gradient capsule that reads as the brightest, most
 * saturated object on screen.
 */
@Composable
internal fun PrimaryActionButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    dark: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val gradient = if (dark) {
        listOf(Color(0xFFFFB2B7), Color(0xFFFF8792))
    } else {
        listOf(Color(0xFFD92D4F), Color(0xFFB3123A))
    }
    val contentColor = if (dark) Color(0xFF67001C) else Color.White
    val alpha = if (enabled) 1f else 0.45f
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(
                elevation = if (enabled) 14.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = gradient.first().copy(alpha = 0.5f),
                spotColor = gradient.first().copy(alpha = 0.5f)
            )
            .photoVaultFocusRing(shape, interactionSource)
            .clip(shape)
            .background(Brush.horizontalGradient(gradient.map { it.copy(alpha = alpha) }))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .testTag("login_primary_action"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = label,
            modifier = Modifier.testTag("login_primary_action_label"),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (LocalLoginForegroundVisible.current) contentColor else Color.Transparent
        )
        if (!loading) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp).testTag("login_primary_action_icon"),
                tint = if (LocalLoginForegroundVisible.current) contentColor else Color.Transparent
            )
        }
    }
}

/**
 * Fields styled as inset glass "wells": a soft translucent fill with no resting
 * outline, so the pane reads as one surface with recesses rather than three
 * separate bordered boxes.
 */
@Composable
private fun glassFieldColors(dark: Boolean): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val foregroundVisible = LocalLoginForegroundVisible.current
    val foreground = if (foregroundVisible) scheme.onSurface else Color.Transparent
    val secondaryForeground = if (foregroundVisible) scheme.onSurfaceVariant else Color.Transparent
    val primaryForeground = if (foregroundVisible) scheme.primary else Color.Transparent
    val well = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.42f)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = if (dark) {
            Color.White.copy(alpha = 0.11f)
        } else {
            Color.White.copy(alpha = 0.62f)
        },
        unfocusedContainerColor = well,
        disabledContainerColor = well.copy(alpha = well.alpha * 0.6f),
        errorContainerColor = well,
        focusedBorderColor = primaryForeground,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedLeadingIconColor = primaryForeground,
        unfocusedLeadingIconColor = secondaryForeground,
        focusedTrailingIconColor = secondaryForeground,
        unfocusedTrailingIconColor = secondaryForeground,
        cursorColor = primaryForeground,
        focusedTextColor = foreground,
        unfocusedTextColor = foreground,
        focusedSupportingTextColor = secondaryForeground,
        unfocusedSupportingTextColor = secondaryForeground
    )
}
