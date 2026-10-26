package dev.ghostguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.core.ButtonMapping
import dev.ghostguard.filter.Tuning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuningScreen(app: GhostGuardApp, modifier: Modifier = Modifier) {
    val config by app.settings.config.collectAsStateWithLifecycle()
    val tuning = config.tuning

    fun edit(transform: (Tuning) -> Tuning) {
        app.settings.update { it.copy(tuning = transform(it.tuning)) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tuning", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Raise a threshold to catch more ghosts, lower it to lose fewer real presses. " +
                "The log tells you which way to go.",
            style = MaterialTheme.typography.bodySmall,
        )

        MsSlider(
            title = "Shortest real press",
            value = tuning.minHoldMs,
            range = 0f..200f,
            help = "A press shorter than this is electrical, not a finger.",
            onChange = { edit { t -> t.copy(minHoldMs = it) } },
        )

        MsSlider(
            title = "Ignore a repeat of the same button for",
            value = tuning.debounceMs,
            range = 0f..1500f,
            help = "The usual culprit: one press arriving several times over.",
            onChange = { edit { t -> t.copy(debounceMs = it) } },
        )

        MsSlider(
            title = "Ignore a different button for",
            value = tuning.crossButtonDebounceMs,
            range = 0f..600f,
            help = "Catches a ghost next-track riding along with a real play press.",
            onChange = { edit { t -> t.copy(crossButtonDebounceMs = it) } },
        )

        MsSlider(
            title = "Long press starts at",
            value = tuning.longPressMs,
            range = 300f..2000f,
            help = null,
            onChange = { edit { t -> t.copy(longPressMs = it) } },
        )

        MsSlider(
            title = "Treat a hold longer than this as a stuck contact",
            value = tuning.maxHoldMs,
            range = 1000f..10000f,
            help = null,
            onChange = { edit { t -> t.copy(maxHoldMs = it) } },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chatter", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${tuning.burstThreshold} presses inside ${tuning.burstWindowMs} ms " +
                        "counts as chatter, and the guard then stays shut for " +
                        "${tuning.chatterCooldownMs} ms.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = tuning.burstThreshold.toFloat(),
                    onValueChange = { edit { t -> t.copy(burstThreshold = it.toInt()) } },
                    valueRange = 2f..10f,
                    steps = 7,
                )
                SliderRow(
                    label = "window",
                    value = tuning.burstWindowMs,
                    range = 300f..3000f,
                    onChange = { edit { t -> t.copy(burstWindowMs = it) } },
                )
                SliderRow(
                    label = "cooldown",
                    value = tuning.chatterCooldownMs,
                    range = 0f..10000f,
                    onChange = { edit { t -> t.copy(chatterCooldownMs = it) } },
                )
            }
        }

        MsSlider(
            title = "Stay shut after plugging in or unplugging",
            value = tuning.plugQuarantineMs,
            range = 0f..5000f,
            help = "Most phantom bursts land in the second after the plug moves.",
            onChange = { edit { t -> t.copy(plugQuarantineMs = it) } },
        )

        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wait for the release", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Needed for the duration check. Turning it off reacts sooner but " +
                            "judges on timing between presses alone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = tuning.requireHoldDuration,
                    onCheckedChange = { on -> edit { t -> t.copy(requireHoldDuration = on) } },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Buttons to block outright", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If one button is the whole problem, switch it off and keep the rest.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (ButtonMapping.transportButtons + ButtonMapping.volumeButtons).forEach { button ->
                        val off = button in tuning.disabledButtons
                        FilterChip(
                            selected = off,
                            onClick = {
                                edit { t ->
                                    t.copy(
                                        disabledButtons = if (off) {
                                            t.disabledButtons - button
                                        } else {
                                            t.disabledButtons + button
                                        }
                                    )
                                }
                            },
                            label = { Text(ButtonMapping.label(button)) },
                        )
                    }
                }
            }
        }

        TextButton(onClick = { edit { Tuning(shadowMode = it.shadowMode) } }) {
            Text("Reset to defaults")
        }
    }
}

@Composable
private fun MsSlider(
    title: String,
    value: Long,
    range: ClosedFloatingPointRange<Float>,
    help: String?,
    onChange: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$title: $value ms", style = MaterialTheme.typography.titleMedium)
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toLong()) },
                valueRange = range,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Long,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Long) -> Unit,
) {
    Column {
        Text("$label: $value ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toLong()) },
            valueRange = range,
        )
    }
}
