package com.example.snapstoneprinter.ui

import com.example.snapstoneprinter.data.api.ScryfallQueryBuilder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tone controls, deliberately behind a sheet.
 *
 * These live off the main surface on purpose: the one big target on the screen is PRINT, and a
 * slider sitting next to it is a slider that gets dragged by accident. Everything here re-dithers
 * the CACHED source art (see [ProxyGeneratorViewModel.scheduleRedither]) - no network round-trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToneSheet(
    uiState: ProxyGeneratorUiState,
    onContrastChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InlineToneControls(
                uiState = uiState,
                onContrastChange = onContrastChange,
                onBrightnessChange = onBrightnessChange,
                onReset = onReset
            )
        }
    }
}

/**
 * The slider block itself. Shared by [ToneSheet] (compact windows) and the expanded-window side
 * panel, so there is exactly one implementation of the tone UI.
 */
@Composable
fun InlineToneControls(
    uiState: ProxyGeneratorUiState,
    onContrastChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        uiState.renderError?.let { RenderErrorNotice(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Dither tone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (uiState.isRedithering) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            text = "Applied before Floyd-Steinberg, on top of auto-levels. " +
                "Re-dithers the art already in memory - never refetches it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        ToneSlider(
            icon = { Icon(Icons.Rounded.Contrast, contentDescription = null) },
            label = "Contrast",
            valueLabel = String.format(Locale.US, "%.2f\u00d7", uiState.contrast),
            value = uiState.contrast,
            range = ProxyGeneratorViewModel.MIN_CONTRAST..ProxyGeneratorViewModel.MAX_CONTRAST,
            onValueChange = onContrastChange
        )

        ToneSlider(
            icon = { Icon(Icons.Rounded.LightMode, contentDescription = null) },
            label = "Brightness",
            valueLabel = "${uiState.brightness.roundToInt()}",
            value = uiState.brightness,
            range = ProxyGeneratorViewModel.MIN_BRIGHTNESS..ProxyGeneratorViewModel.MAX_BRIGHTNESS,
            onValueChange = onBrightnessChange
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        TextButton(
            onClick = onReset,
            enabled = !uiState.isToneMappingNeutral,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Reset to auto-levels")
        }
    }
}

@Composable
internal fun RenderErrorNotice(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}

@Composable
private fun ToneSlider(
    icon: @Composable () -> Unit,
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        )
    }
}

/**
 * Force-generate one exact card by name instead of rolling randomly.
 *
 * Doubles as a quick way to pull up a specific split/flip/adventure/transform/modal_dfc card to
 * check the renderer, instead of waiting on `cards/random` to hit one of those layouts by chance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardSearchSheet(
    isLoading: Boolean,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Find a card",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Fuzzy-matched by name, same as the Scryfall search bar - typos and " +
                    "partial names are fine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Card name") },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { if (query.isNotBlank()) onSearch(query) }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            FilledTonalButton(
                onClick = { if (query.isNotBlank()) onSearch(query) },
                enabled = !isLoading && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate")
                }
            }
        }
    }
}

/**
 * MomirVig mode's CMC picker: activating Momir Vig conjures a token copy of a random CREATURE
 * card of the chosen converted mana cost. One tap fetches immediately - there is no separate
 * confirm step, since re-opening this same full-screen prompt (via the top bar's roll action) is
 * how the user picks a different CMC for the next creature.
 *
 * Full-screen rather than a sheet so the 17 CMC tiles can be large and easy to tap accurately,
 * instead of competing for space as small chips.
 */
@Composable
fun MomirVigCmcScreen(
    isLoading: Boolean,
    isFunny: Boolean,
    onToggleFunny: (Boolean) -> Unit,
    onPickCmc: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .statusBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "Pick a converted mana cost",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = "Momir Vig conjures a random creature card of this CMC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(ScryfallQueryBuilder.MOMIR_VIG_CMC_RANGE.toList()) { cmc ->
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CmcTile(cmc = cmc, enabled = !isLoading, onClick = { onPickCmc(cmc) })
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Include funny cards",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Switch(
                        checked = isFunny,
                        onCheckedChange = onToggleFunny,
                        enabled = !isLoading
                    )
                }
            }
        }
    }
}

/**
 * One large, tappable CMC value in [MomirVigCmcScreen]'s grid. One solid theme color throughout
 * (not alternating pastels) reads as a single deliberate button style rather than a novelty grid.
 * Fixed size rather than filling its whole grid cell - stretching to a full 1/3-screen-width
 * square would make 6 rows taller than most phone screens; a capped size keeps all 6 rows visible
 * without scrolling, centered in their cells by the caller.
 */
@Composable
private fun CmcTile(cmc: Int, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.size(100.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = cmc.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Session history. Each row is one PULL, which may be more than one slip - reprinting a DFC fires
 * both dispatches again, in order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>,
    onReprint: (HistoryEntry) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "This session",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Last ${ProxyGeneratorViewModel.MAX_HISTORY} pulls. Reprint sends every " +
                    "slip again, one at a time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            error?.let { RenderErrorNotice(it) }

            if (history.isEmpty()) {
                Text(
                    text = "Nothing pulled yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(history, key = { it.id }) { entry ->
                        HistoryRow(entry = entry, onReprint = { onReprint(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onReprint: () -> Unit) {
    val bitmap = entry.slips.firstOrNull()?.bitmap
    val thumbnail = remember(bitmap) { bitmap?.asImageBitmap() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .testTag("history-thumbnail-${entry.id}")
                    .size(44.dp, 56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.White),
                contentScale = ContentScale.Crop,
                // Same reasoning as the main preview: this is 1-bit output, do not smooth it.
                filterQuality = FilterQuality.None
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.cardName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(entry.typeLine ?: "")
                    if (entry.slipCount > 1) {
                        if (isNotEmpty()) append(" \u2014 ")
                        append("${entry.slipCount} slips")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onReprint, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Print, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Reprint")
        }
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=914dp,dpi=420")
@Composable
private fun ToneSheetContentPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(24.dp)) {
            ToneSlider(
                icon = { Icon(Icons.Rounded.Contrast, contentDescription = null) },
                label = "Contrast",
                valueLabel = "1.40\u00d7",
                value = 1.4f,
                range = 0.5f..3f,
                onValueChange = {}
            )
        }
    }
}
