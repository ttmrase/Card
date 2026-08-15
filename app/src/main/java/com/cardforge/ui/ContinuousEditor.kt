package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Surface2

private enum class ContinuousType(val label: String) {
    PROTECTION("耐性を与える"),
    STAT_BUFF("攻撃力・守備力を増減する"),
    CANNOT_ATTACK("攻撃できなくする")
}

/**
 * 【永続効果】の編集。発動を必要とせず、表側でフィールドにある限り
 * ずっと適用される効果を並べる。
 */
@Composable
fun ContinuousEffectSection(
    effects: List<ContinuousEffect>,
    master: MasterData,
    onChange: (List<ContinuousEffect>) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    SectionCard(title = "永続効果") {
        Text(
            "発動しなくても、表側でフィールドにある限りずっと適用される効果です。" +
                "「このカードは相手の効果を受けない」のような書き方ができます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (effects.isEmpty()) {
            Text(
                "永続効果はありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        effects.forEachIndexed { index, effect ->
            Surface(
                color = Surface2,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        EffectTextRenderer.continuousToText(effect, master) + "。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onChange(effects.toMutableList().also { it.removeAt(index) })
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "削除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Chip("＋ 永続効果を追加") { showDialog = true }
    }

    if (showDialog) {
        ContinuousEffectDialog(
            master = master,
            onDismiss = { showDialog = false },
            onConfirm = {
                onChange(effects + it)
                showDialog = false
            }
        )
    }
}

@Composable
private fun ContinuousEffectDialog(
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (ContinuousEffect) -> Unit
) {
    var type by remember { mutableStateOf(ContinuousType.PROTECTION) }
    var appliesToSelf by remember { mutableStateOf(true) }
    var scope by remember {
        mutableStateOf(
            CardScope(
                who = PlayerRef.SELF,
                zone = ZoneType.MONSTER_ZONE,
                selection = SelectionMode.ALL
            )
        )
    }
    var protection by remember { mutableStateOf(ProtectionKind.OPPONENT_EFFECTS) }
    var stat by remember { mutableStateOf(StatKind.ATK) }
    var amount by remember { mutableIntStateOf(500) }

    val target = if (appliesToSelf) null else scope

    fun build(): ContinuousEffect = when (type) {
        ContinuousType.PROTECTION -> ProtectionEffect(target, protection)
        ContinuousType.STAT_BUFF -> StatBuffEffect(target, stat, amount)
        ContinuousType.CANNOT_ATTACK -> CannotAttackEffect(target)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("永続効果を追加") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown("効果の種類", ContinuousType.entries.toList(), type, { it.label }) {
                    type = it
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = appliesToSelf,
                        onCheckedChange = { appliesToSelf = it }
                    )
                    Text("このカード自身に適用する")
                }
                if (!appliesToSelf) {
                    HorizontalDivider()
                    CardScopeEditor(scope, master) { scope = it }
                }

                when (type) {
                    ContinuousType.PROTECTION -> Dropdown(
                        "耐性", ProtectionKind.all, protection, { it.label }
                    ) { protection = it }

                    ContinuousType.STAT_BUFF -> {
                        Dropdown("対象の数値", StatKind.all, stat, { it.label }) { stat = it }
                        NumberField("増減量（マイナスで下げる）", amount) { amount = it }
                    }

                    ContinuousType.CANNOT_ATTACK -> Unit
                }

                HorizontalDivider()
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Text(
                        EffectTextRenderer.continuousToText(build(), master) + "。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(build()) }) { Text("決定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
