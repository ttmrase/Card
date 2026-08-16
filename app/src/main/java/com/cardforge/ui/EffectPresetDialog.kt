package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.model.CardDef
import com.cardforge.model.CardKind
import com.cardforge.model.EffectPreset
import com.cardforge.model.MasterData
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Surface2

/**
 * 保存した効果を選んで呼び出すダイアログ。
 *
 * 「置き換える」は編集中の効果をまるごと差し替え、
 * 「効果を追加」は今の効果の後ろに①②…として足す。
 */
@Composable
fun EffectPresetDialog(
    presets: List<EffectPreset>,
    master: MasterData,
    kind: CardKind,
    onDismiss: () -> Unit,
    onReplace: (EffectPreset) -> Unit,
    onAppend: (EffectPreset) -> Unit,
    onDelete: (EffectPreset) -> Unit,
    onRename: (EffectPreset, String) -> Unit
) {
    var renaming by remember { mutableStateOf<EffectPreset?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存した効果") },
        text = {
            if (presets.isEmpty()) {
                Text("保存した効果がありません。")
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        val open = expanded == preset.id
                        Surface(
                            color = Surface2,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        preset.name,
                                        fontWeight = FontWeight.Bold,
                                        color = Gold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        expanded = if (open) null else preset.id
                                    }) { Text(if (open) "閉じる" else "中身を見る") }
                                }

                                if (open) {
                                    val preview = CardDef(
                                        id = preset.id,
                                        name = preset.name,
                                        kind = kind,
                                        effect = preset.effect
                                    )
                                    val text = EffectTextRenderer.renderGenerated(preview, master)
                                    if (text.isBlank()) {
                                        Text(
                                            "（効果が空です）",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        EffectTextView(text)
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onReplace(preset) }) {
                                        Text("置き換える")
                                    }
                                    TextButton(onClick = { onAppend(preset) }) {
                                        Text("効果を追加")
                                    }
                                    TextButton(onClick = { renaming = preset }) {
                                        Text("名前")
                                    }
                                    TextButton(onClick = { onDelete(preset) }) {
                                        Text("削除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )

    renaming?.let { preset ->
        NameInputDialog(
            title = "効果の名前を変更",
            initial = preset.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                onRename(preset, name)
                renaming = null
            }
        )
    }
}
