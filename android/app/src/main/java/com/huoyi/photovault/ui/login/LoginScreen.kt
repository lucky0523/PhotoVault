package com.huoyi.photovault.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
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
import com.huoyi.photovault.ui.theme.GlassBar
import com.huoyi.photovault.ui.theme.LocalGlassBackdrop
import com.huoyi.photovault.ui.theme.PhotoVaultColors
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Login screen.
 *
 * The whole page fits on one screen without scrolling. The credentials pane stays
 * centered within the space remaining above the keyboard.
 *
 * Behind everything, a wall of photographs drifts continuously: three columns of
 * prints scrolling at different speeds and directions, tilted a few degrees. It
 * establishes what the app is for without a word of marketing copy, and it gives
 * the liquid-glass form real texture to blur and refract as the pictures pass
 * behind it.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // QR scanner: the web console shows a server QR code that encodes the full
    // server URL (e.g. http://192.168.1.100:8000). Scanning it fills the field.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.takeIf { it.isNotEmpty() }?.let { scanned ->
            viewModel.onServerAddressChange(scanned)
        }
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

    // Handle auto-login
    LaunchedEffect(uiState.hasValidToken) {
        if (uiState.hasValidToken) {
            onLoginSuccess()
        }
    }

    // Handle login success
    LaunchedEffect(uiState.loginSuccess) {
        if (uiState.loginSuccess) {
            onLoginSuccess()
        }
    }

    // The connection-test outcome is shown inline (status pill next to the server
    // section) rather than as a snackbar, so it stays visually attached to the
    // field it describes. Clear it after a beat so it can't read as stale truth.
    LaunchedEffect(uiState.testResult) {
        if (uiState.testResult != null) {
            delay(5000)
            viewModel.clearTestResult()
        }
    }

    val dark = isSystemInDarkTheme()
    val scrimBrush = remember(dark) { photoWallScrim(dark) }
    val scrimTint = if (dark) {
        PhotoVaultColors.VaultBlue.copy(alpha = 0.09f)
    } else {
        PhotoVaultColors.VaultBlue.copy(alpha = 0.06f)
    }
    val baseColor = if (dark) Color(0xFF070D18) else Color(0xFFEFF4FF)

    // Backdrop captured for the glass form to sample. It holds the photo wall and
    // its scrim, never the form itself — sampling a layer that contains the glass
    // would feed back on itself.
    val bgBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(bgBackdrop)
                .background(baseColor)
                .drawWithContent {
                    drawContent()
                    // Knock the photographs back so the form stays legible;
                    // strongest at the top and bottom edges.
                    drawRect(scrimBrush)
                    drawRect(scrimTint)
                }
        ) {
            ScrollingPhotoWall(modifier = Modifier.fillMaxSize())
        }

        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 24.dp, top = 64.dp),
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            maxLines = 1
        )

        CompositionLocalProvider(LocalGlassBackdrop provides bgBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                    .padding(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Credentials pane — real frosted glass that refracts + blurs the
                // moving photo wall (falls back to a translucent card pre-API 31).
                GlassBar(
                    modifier = Modifier.fillMaxWidth(),
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
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            ConnectionStatusPill(
                                isTesting = uiState.isTesting,
                                testResult = uiState.testResult,
                                onTest = viewModel::testConnection,
                                enabled = !uiState.isLoading && !uiState.isTesting
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // One notice slot for both failure kinds: a failed login
                        // and a failed connection test are the same problem class
                        // from the user's point of view, so they share one banner
                        // instead of a banner plus a snackbar.
                        val notice = uiState.errorMessage
                            ?: (uiState.testResult as? TestConnectionResult.Failure)?.message
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

                        // Server address — the one field users can't guess, so it
                        // carries the QR shortcut as a trailing action.
                        OutlinedTextField(
                            value = uiState.serverAddress,
                            onValueChange = viewModel::onServerAddressChange,
                            placeholder = { Text("服务器地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                            isError = uiState.serverAddressError != null,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
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
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            supportingText = {
                                val error = uiState.serverAddressError
                                Text(
                                    text = error ?: "可扫描 Web 端的服务器二维码自动填写",
                                    color = if (error == null) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            },
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
                            onValueChange = viewModel::onUsernameChange,
                            placeholder = { Text("用户名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                            isError = uiState.usernameError != null,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
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
                            onValueChange = viewModel::onPasswordChange,
                            placeholder = { Text("密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                            isError = uiState.passwordError != null,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
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
                                    viewModel.login()
                                }
                            ),
                            enabled = !uiState.isLoading
                        )

                        // Whole row toggles, so the label is part of the hit target.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(
                                    enabled = !uiState.isLoading,
                                    role = Role.Switch,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    viewModel.onRememberPasswordChange(!uiState.rememberPassword)
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "记住密码",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Switch(
                                checked = uiState.rememberPassword,
                                onCheckedChange = viewModel::onRememberPasswordChange,
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
                            onClick = viewModel::login
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Scrolling photo wall
// ---------------------------------------------------------------------------

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
private fun photoWallScrim(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(
        0.00f to Color(0xFF070D18).copy(alpha = 0.90f),
        0.20f to Color(0xFF070D18).copy(alpha = 0.68f),
        0.58f to Color(0xFF070D18).copy(alpha = 0.58f),
        1.00f to Color(0xFF070D18).copy(alpha = 0.94f)
    )
} else {
    // A restrained dark wash preserves the photographs' colour and contrast
    // without making the light-theme form difficult to read.
    Brush.verticalGradient(
        0.00f to Color.Black.copy(alpha = 0.52f),
        0.20f to Color.Black.copy(alpha = 0.38f),
        0.58f to Color.Black.copy(alpha = 0.32f),
        1.00f to Color.Black.copy(alpha = 0.50f)
    )
}

// ---------------------------------------------------------------------------
// Form pieces
// ---------------------------------------------------------------------------

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
private fun ConnectionStatusPill(
    isTesting: Boolean,
    testResult: TestConnectionResult?,
    onTest: () -> Unit,
    enabled: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val look = when {
        isTesting -> PillLook("测试中", scheme.primary, scheme.onPrimary, null)
        testResult is TestConnectionResult.Success -> PillLook(
            "已连通",
            PhotoVaultColors.SyncGreen,
            PhotoVaultColors.DeepVault,
            Icons.Rounded.CheckCircle
        )
        testResult is TestConnectionResult.Failure -> PillLook(
            stringResource(R.string.connection_status_failed),
            scheme.error,
            PhotoVaultColors.DeepVault,
            Icons.Rounded.ErrorOutline
        )
        else -> PillLook("测试连接", scheme.primary, scheme.onPrimary, null)
    }

    Row(
        modifier = Modifier
            .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(look.containerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onTest)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = look.icon
        if (isTesting) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = look.contentColor
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = look.contentColor
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = look.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = look.contentColor,
            maxLines = 1
        )
    }
}

/**
 * The single primary action: a gradient capsule that reads as the brightest, most
 * saturated object on screen.
 */
@Composable
private fun PrimaryActionButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    dark: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val gradient = if (dark) {
        listOf(Color(0xFFA8C2FF), Color(0xFFC9B6FF))
    } else {
        listOf(PhotoVaultColors.VaultBlue, Color(0xFF6C5CFF))
    }
    val contentColor = if (dark) Color(0xFF09245F) else Color.White
    val alpha = if (enabled) 1f else 0.45f

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
            .clip(shape)
            .background(Brush.horizontalGradient(gradient.map { it.copy(alpha = alpha) }))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
        if (!loading) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor
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
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedLeadingIconColor = scheme.primary,
        unfocusedLeadingIconColor = scheme.onSurfaceVariant,
        cursorColor = scheme.primary,
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        focusedSupportingTextColor = scheme.onSurfaceVariant,
        unfocusedSupportingTextColor = scheme.onSurfaceVariant
    )
}
