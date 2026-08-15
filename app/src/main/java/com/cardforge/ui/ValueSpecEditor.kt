package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Surface2

/**
 * 数値の指定。固定値のほか「条件を満たすカードの枚数×係数」を組み立てられる。
 */
@Composable
fun ValueSpecEditor(
    label: String,
    spec: ValueSpec,
    master: MasterData,
    allowNegative: Boolean = false,
    onChange: (ValueSpec) -> Unit
) {
    val counting = spec is CountValue

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = counting,
                onCheckedChange = { useCount ->
                    onChange(
                        if (useCount) CountValue()
                        else FixedValue((spec as? FixedValue)?.value ?: 0)
                    )
                }
            )
            Text("カードの枚数で決める")
        }

        when (spec) {
            is FixedValue -> NumberField(
                label = label + if (allowNegative) "（マイナスで下げる）" else "",
                value = spec.value
            ) { onChange(FixedValue(it)) }

            is CountValue -> {
                Text(
                    "数えるカード",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CardScopeEditor(
                    scope = spec.scope,
                    master = master,
                    showCount = false
                ) { onChange(spec.copy(scope = it)) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("1枚あたり", spec.multiplier, Modifier.weight(1f)) {
                        onChange(spec.copy(multiplier = it))
                    }
                    NumberField("固定で足す", spec.base, Modifier.weight(1f)) {
                        onChange(spec.copy(base = it))
                    }
                }
            }
        }

        HorizontalDivider()
        Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
            Text(
                EffectTextRenderer.valueToText(spec, master),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}
