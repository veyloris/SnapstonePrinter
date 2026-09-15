package com.example.snapstoneprinter.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.snapstoneprinter.data.print.ChosenComponentReceiver
import com.example.snapstoneprinter.image.PrintSlip

/**
 * Colour of unburned thermal paper. Not a theme token on purpose: the slip preview is a physical
 * proof of what the printer will emit, so it must stay white in light AND dark theme. Tinting it
 * with a surface colour would misrepresent the print.
 */
private val ThermalPaper = Color.White

/** Scryfall `normal` card images are 488x680; this is their aspect ratio. */
private const val CARD_ASPECT_RATIO = 488f / 680f

/**
 * Which game mode the top bar's switcher is set to.
 *
 * Snapstone Wielder's roll action fetches a random card directly; MomirVig's roll action opens
 * [MomirVigCmcSheet] instead, since conjuring a creature needs a chosen converted mana cost first.
 */
enum class AppMode(val displayName: String) {
    SNAPSTONE_WIELDER("Snapstone Wielder"),
    MOMIR_VIG("MomirVig")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyGeneratorScreen(
    viewModel: ProxyGeneratorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showToneSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showMomirVigSheet by remember { mutableStateOf(false) }
    var appMode by remember { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }

    // Snapstone Wielder rolls immediately; MomirVig needs a CMC first, so its roll action opens
    // the picker sheet instead - the SAME control doubles as "pick a different CMC" later.
    val onRoll: () -> Unit = if (appMode == AppMode.SNAPSTONE_WIELDER) {
        viewModel::fetchRandomCard
    } else {
        { showMomirVigSheet = true }
    }

    // Hoisted so the "Print" action in the bottom bar always targets the slip the user is
    // actually looking at in the pager.
    val pagerState = rememberPagerState(pageCount = { uiState.slips.size })

    // ONE dispatch at a time. The result callback is what advances to the next slip, so slip 2 of
    // a transform card physically cannot leave before slip 1's share activity has returned.
    val dispatchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onSlipDispatched()
    }

    val dispatch = uiState.dispatch
    LaunchedEffect(dispatch?.requestId, dispatch?.index) {
        val job = dispatch ?: return@LaunchedEffect
        val send = buildSendIntent(job.current)

        // Straight to the remembered printer app when it is still installed; otherwise the
        // chooser, wired up so we learn what the user picks.
        val storedTarget = uiState.printerTarget
        val remembered = storedTarget?.takeIf { viewModel.isTargetUsable(it) }
        val launched = if (remembered != null) {
            runCatching {
                dispatchLauncher.launch(Intent(send).setComponent(remembered.component))
            }.isSuccess
        } else {
            false
        }

        if (!launched) {
            // Forget a stale target whether it failed the isTargetUsable check up front (the
            // app was uninstalled) or passed that check but still failed to actually launch -
            // either way the persisted target is dead and the overflow menu must stop lying
            // about it being set.
            if (storedTarget != null) viewModel.forgetPrinterTarget()
            try {
                dispatchLauncher.launch(
                    Intent.createChooser(
                        send,
                        "Print slip ${job.index + 1} of ${job.total} \u2014 ${job.label}",
                        chosenComponentSender(context)
                    )
                )
            } catch (e: ActivityNotFoundException) {
                viewModel.cancelDispatch("No app on this device can receive a PNG.")
            }
        }
    }

    Scaffold(
        topBar = {
            // Every destructive-ish action (re-roll, which throws away the current card) lives up
            // here, deliberately out of thumb reach. The bottom button only ever prints.
            ProxyTopBar(
                uiState = uiState,
                appMode = appMode,
                onAppModeChange = { appMode = it },
                onFetchRandom = onRoll,
                onToggleFunny = viewModel::toggleIsFunny,
                onOpenTone = { showToneSheet = true },
                onOpenHistory = { historyError = null; showHistorySheet = true },
                onOpenSearch = { showSearchSheet = true },
                onForgetTarget = viewModel::forgetPrinterTarget
            )
        },
        bottomBar = {
            PrintBar(
                uiState = uiState,
                onPrint = viewModel::printCurrentCard
            )
        },
        modifier = modifier
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            val isExpanded = maxWidth > 600.dp

            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.6f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PreviewModeToggle(
                            uiState = uiState,
                            onSelect = viewModel::setPreviewMode
                        )
                        ProxyPreview(
                            uiState = uiState,
                            pagerState = pagerState,
                            // Height-bounded by the Row, so the slip scrolls internally here.
                            scrollContent = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                    ProxyControls(
                        uiState = uiState,
                        appMode = appMode,
                        onFetchRandom = onRoll,
                        onToggleFunny = viewModel::toggleIsFunny,
                        onContrastChange = viewModel::setContrast,
                        onBrightnessChange = viewModel::setBrightness,
                        onResetTone = viewModel::resetToneMapping,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CardHeadline(
                        uiState = uiState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    PreviewModeToggle(
                        uiState = uiState,
                        onSelect = viewModel::setPreviewMode
                    )
                    ProxyPreview(
                        uiState = uiState,
                        pagerState = pagerState,
                        // The preview owns every remaining pixel: it is the whole point of the
                        // screen. weight(1f) gives it a BOUNDED height, which is also what keeps
                        // the internal slip scroll legal (see [ProxyPreview.scrollContent]).
                        scrollContent = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }
    }

    if (showToneSheet) {
        ToneSheet(
            uiState = uiState,
            onContrastChange = viewModel::setContrast,
            onBrightnessChange = viewModel::setBrightness,
            onReset = viewModel::resetToneMapping,
            onDismiss = { showToneSheet = false }
        )
    }

    if (showHistorySheet) {
        HistorySheet(
            history = uiState.history,
            onReprint = {
                historyError = null
                if (viewModel.tryReprint(it)) {
                    showHistorySheet = false
                } else {
                    historyError = "This item is unavailable or busy. Wait for current work to finish and try again."
                }
            },
            onDismiss = { historyError = null; showHistorySheet = false },
            error = historyError
        )
    }

    if (showSearchSheet) {
        CardSearchSheet(
            isLoading = uiState.isLoading,
            onSearch = {
                showSearchSheet = false
                viewModel.fetchCardByName(it)
            },
            onDismiss = { showSearchSheet = false }
        )
    }

    if (showMomirVigSheet) {
        MomirVigCmcScreen(
            isLoading = uiState.isLoading,
            isFunny = uiState.isFunny,
            onToggleFunny = viewModel::toggleIsFunny,
            onPickCmc = {
                showMomirVigSheet = false
                viewModel.fetchMomirVigCreature(it)
            },
            onDismiss = { showMomirVigSheet = false }
        )
    }
}

/**
 * A single-image `ACTION_SEND`.
 *
 * NEVER `ACTION_SEND_MULTIPLE`: the cheap Bluetooth thermal printer apps this targets either print
 * only the first stream or interleave them. Two slips means two of these, in sequence.
 */
private fun buildSendIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/png"
    putExtra(Intent.EXTRA_STREAM, uri)
    // clipData carries the grant for receivers that read it instead of EXTRA_STREAM.
    clipData = ClipData.newRawUri("Proxy slip", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * The chooser-result callback. This is the ONLY supported way to find out which app the user
 * picked; `startActivityForResult` on a chooser does not tell you.
 *
 * Must be `FLAG_MUTABLE` - the system fills in `EXTRA_CHOSEN_COMPONENT` itself.
 */
private fun chosenComponentSender(context: Context) = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, ChosenComponentReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
).intentSender

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyTopBar(
    uiState: ProxyGeneratorUiState,
    appMode: AppMode,
    onAppModeChange: (AppMode) -> Unit,
    onFetchRandom: () -> Unit,
    onToggleFunny: (Boolean) -> Unit,
    onOpenTone: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSearch: () -> Unit,
    onForgetTarget: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var modeMenuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !uiState.isLoading) { modeMenuExpanded = true }
                        // The bar itself is already primaryContainer - a scrim of onPrimaryContainer
                        // is the only thing that stays visible against it in both light and dark.
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = appMode.displayName,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "Switch game mode",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                DropdownMenu(
                    expanded = modeMenuExpanded,
                    onDismissRequest = { modeMenuExpanded = false }
                ) {
                    AppMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            onClick = {
                                modeMenuExpanded = false
                                onAppModeChange(mode)
                            }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        actions = {
            // Primary re-roll. One tap, but it is all the way up here so it cannot be hit by a
            // thumb that was aiming for PRINT. In MomirVig mode this opens the CMC picker instead
            // of fetching directly - see AppMode's doc.
            val rollLabel = if (appMode == AppMode.SNAPSTONE_WIELDER) {
                "Random card"
            } else {
                "Pick a CMC"
            }
            IconButton(
                onClick = onFetchRandom,
                enabled = !uiState.isLoading
            ) {
                Icon(
                    Icons.Rounded.Casino,
                    contentDescription = rollLabel
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !uiState.isLoading
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(rollLabel) },
                        leadingIcon = { Icon(Icons.Rounded.Casino, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onFetchRandom()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Find card by name") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenSearch()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Adjust dither tone") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                        enabled = uiState.slips.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            onOpenTone()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Session history (${uiState.history.size})") },
                        leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenHistory()
                        }
                    )
                    HorizontalDivider()
                    // The visible escape hatch: the remembered printer app is sticky by design,
                    // so there must always be an obvious way to change it.
                    DropdownMenuItem(
                        text = {
                            Text(
                                uiState.printerTarget?.let { "Change printer app (${it.label})" }
                                    ?: "Printer app: ask every time"
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                        enabled = uiState.printerTarget != null,
                        onClick = {
                            menuExpanded = false
                            onForgetTarget()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Include funny cards") },
                        trailingIcon = {
                            Switch(
                                checked = uiState.isFunny,
                                onCheckedChange = null
                            )
                        },
                        onClick = { onToggleFunny(!uiState.isFunny) }
                    )
                }
            }
        }
    )
}

/**
 * Thermal composite (what actually prints) vs the full-resolution card image.
 *
 * Small, chip-sized, and nowhere near the bottom button - switching views must never be confusable
 * with printing.
 */
@Composable
private fun PreviewModeToggle(
    uiState: ProxyGeneratorUiState,
    onSelect: (PreviewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.slips.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        FilterChip(
            selected = uiState.previewMode == PreviewMode.THERMAL,
            onClick = { onSelect(PreviewMode.THERMAL) },
            label = { Text("Thermal") },
            modifier = Modifier.heightIn(min = 40.dp)
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = uiState.previewMode == PreviewMode.FULL_CARD,
            onClick = { onSelect(PreviewMode.FULL_CARD) },
            label = { Text("Full card") },
            modifier = Modifier.heightIn(min = 40.dp)
        )
    }
}

/**
 * The one large, thumb-reachable target on the screen. It only ever prints - never re-rolls.
 *
 * For a double-faced card this queues BOTH slips; they leave one after the other.
 */
@Composable
private fun PrintBar(
    uiState: ProxyGeneratorUiState,
    onPrint: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            uiState.dispatch?.let { job ->
                Text(
                    text = "Sending slip ${job.index + 1} of ${job.total}\u2026",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            uiState.artError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Button(
                onClick = onPrint,
                enabled = uiState.canPrint && uiState.dispatch == null,
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
            ) {
                Icon(Icons.Rounded.Print, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (uiState.slips.size > 1) {
                        "PRINT \u2014 ${uiState.slips.size} slips"
                    } else {
                        "PRINT"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Compact name + type line. Intentionally two lines max so it never crowds the preview. */
@Composable
private fun CardHeadline(
    uiState: ProxyGeneratorUiState,
    modifier: Modifier = Modifier
) {
    val card = uiState.currentCard ?: return
    Column(modifier = modifier) {
        Text(
            text = card.effectiveName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        card.effectiveTypeLine?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ProxyPreview(
    uiState: ProxyGeneratorUiState,
    modifier: Modifier = Modifier,
    pagerState: PagerState? = null,
    /**
     * Whether the slip content scrolls inside this component.
     *
     * MUST be false whenever an ancestor is already vertically scrollable. A scrollable nested
     * directly inside another vertical scroll is measured with an infinite maximum height, which
     * Compose rejects at measure time:
     *
     * ```
     * IllegalStateException: Vertically scrollable component was measured with an infinity
     * maximum height constraints
     * ```
     *
     * That throws from the draw/measure pass on the main thread, so it kills the whole activity
     * rather than degrading. It only reproduces once [ProxyGeneratorUiState.slips] is non-empty,
     * which is why it stayed hidden while every render was failing upstream.
     *
     * The false branch also skips the pager: [HorizontalPager] needs a bounded main-axis size and
     * a definite cross-axis size, neither of which it gets under an infinite height constraint.
     * So the unbounded case falls back to a plain stacked column, which measures fine.
     */
    scrollContent: Boolean = true
) {
    // Hoisted so the slot table shape does not depend on which branch renders below.
    val localPagerState = rememberPagerState(pageCount = { uiState.slips.size })
    val activePagerState = pagerState ?: localPagerState

    // A fresh card must start on slip 1, otherwise a 1-slip card after a 2-slip card can land on a
    // clamped page with no scroll animation.
    LaunchedEffect(uiState.slips) {
        if (uiState.slips.isNotEmpty()) activePagerState.scrollToPage(0)
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = if (scrollContent) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Fetching card…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (uiState.error != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (uiState.slips.isNotEmpty()) {
                Column(
                    modifier = if (scrollContent) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    uiState.renderError?.let { RenderErrorNotice(it, Modifier.padding(12.dp)) }
                    if (uiState.slips.size > 1) {
                        MultiSlipHeader(
                            slips = uiState.slips,
                            currentPage = activePagerState.currentPage,
                            paged = scrollContent
                        )
                    }

                    if (scrollContent) {
                        HorizontalPager(
                            state = activePagerState,
                            pageSpacing = 12.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) { page ->
                            // Each page is its own vertical scroller: a 384x~1100px slip rendered
                            // at full width is far taller than the viewport, and we would rather
                            // let the user scroll it than shrink it.
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                SlipImage(
                                    slip = uiState.slips[page],
                                    previewMode = uiState.previewMode
                                )
                            }
                        }
                    } else {
                        // Unbounded-height fallback: stack every slip, no nested scrolling.
                        uiState.slips.forEach { slip ->
                            SlipImage(
                                slip = slip,
                                previewMode = uiState.previewMode,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Rounded.Casino,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No proxy generated yet \u2014 tap the dice in the top bar",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Renders one slip at FULL WIDTH.
 *
 * Two things here are load-bearing and must not be "cleaned up":
 *
 * 1. [ContentScale.FillWidth] + [aspectRatio] derived from the bitmap. The old code used
 *    `wrapContentHeight()` + [ContentScale.Fit], which capped the height at the bitmap's intrinsic
 *    384x~1100 px and therefore drew the slip near 1:1 - about 0.8 inch wide on a 480dpi phone.
 *
 * 2. [FilterQuality.None] - nearest-neighbour sampling. The bitmap is 1-bit Floyd-Steinberg
 *    dithered art being upscaled roughly 3x. Default bilinear filtering would blur the dither's
 *    black/white pixel scatter into smooth grey. That looks prettier but actively MISREPRESENTS
 *    what the thermal printer will output. This preview is a proof of the print, so every dot
 *    stays individually visible. DO NOT change this to a smoothing filter.
 *
 * [PreviewMode.FULL_CARD] swaps in the original Scryfall art as a REFERENCE only. That image is a
 * continuous-tone photograph, so it is free to use normal filtering - the no-smoothing rule exists
 * for the 1-bit thermal composite and applies to it alone.
 */
@Composable
private fun SlipImage(
    slip: PrintSlip,
    modifier: Modifier = Modifier,
    previewMode: PreviewMode = PreviewMode.THERMAL
) {
    if (previewMode == PreviewMode.FULL_CARD && !slip.fullImageUrl.isNullOrBlank()) {
        AsyncImage(
            model = slip.fullImageUrl,
            contentDescription = "Full resolution card image for ${slip.faceName}",
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT_RATIO)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.FillWidth
        )
        return
    }

    val imageBitmap = remember(slip.bitmap) { slip.bitmap.asImageBitmap() }
    val slipAspectRatio = remember(slip.bitmap) {
        slip.bitmap.width.toFloat() / slip.bitmap.height.toFloat().coerceAtLeast(1f)
    }

    Image(
        bitmap = imageBitmap,
        contentDescription = "Generated proxy slip for ${slip.faceName}",
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(slipAspectRatio)
            .clip(MaterialTheme.shapes.small)
            .background(ThermalPaper),
        contentScale = ContentScale.FillWidth,
        filterQuality = FilterQuality.None
    )
}

/** Makes it unmistakable that a DFC prints more than one physical slip. */
@Composable
private fun MultiSlipHeader(
    slips: List<PrintSlip>,
    currentPage: Int,
    paged: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val safePage = currentPage.coerceIn(0, slips.lastIndex)
        Text(
            text = if (paged) {
                "Slip ${safePage + 1} of ${slips.size} \u2014 ${slips[safePage].faceName}"
            } else {
                "${slips.size} slips \u2014 all of them print"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (paged) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slips.indices.forEach { index ->
                    val selected = index == safePage
                    Box(
                        modifier = Modifier
                            .size(if (selected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Swipe \u2014 this card needs ${slips.size} printed slips",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Expanded / tablet side panel. Fetch actions are safe to surface here because on a large window
 * they are nowhere near the primary print target.
 */
@Composable
fun ProxyControls(
    uiState: ProxyGeneratorUiState,
    appMode: AppMode,
    onFetchRandom: () -> Unit,
    onToggleFunny: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onContrastChange: (Float) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onResetTone: () -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Controls",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        uiState.currentCard?.let { card ->
            Text(
                text = card.effectiveName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            card.effectiveTypeLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (uiState.slips.size > 1) {
                Text(
                    text = "${uiState.slips.size} slips to print",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider()
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Include funny cards",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = uiState.isFunny,
                onCheckedChange = onToggleFunny,
                enabled = !uiState.isLoading
            )
        }

        OutlinedButton(
            onClick = onFetchRandom,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading,
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(Icons.Rounded.Casino, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (appMode == AppMode.SNAPSTONE_WIELDER) "Random card" else "Pick a CMC")
        }

        if (uiState.slips.isNotEmpty()) {
            HorizontalDivider()
            InlineToneControls(
                uiState = uiState,
                onContrastChange = onContrastChange,
                onBrightnessChange = onBrightnessChange,
                onReset = onResetTone
            )
        }
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=914dp,dpi=420")
@Composable
fun ProxyPreviewEmptyPreview() {
    MaterialTheme {
        ProxyPreview(
            uiState = ProxyGeneratorUiState(
                isLoading = false,
                error = null,
                slips = emptyList()
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        )
    }
}
