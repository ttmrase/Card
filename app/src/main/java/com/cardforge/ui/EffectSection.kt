package com.cardforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.game.EffectNumbers
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Surface2

/**
 * 効果テキストのエディタ。
 *
 * 上段が効果番号より前に書かれる共通の【場所】【条件】【コスト】、
 * 下段が ①②… の各効果。各効果は自前の【場所】【条件】【コスト】を持てる。
 */
@Composable
fun EffectEditorSection(
    kind: CardKind,
    effect: EffectText,
    master: MasterData,
    onChange: (EffectText) -> Unit
) {
    var conditionTarget by remember { mutableStateOf<Int?>(null) }
    var costTarget by remember { mutableStateOf<Int?>(null) }
    var actionTarget by remember { mutableStateOf<Pair<Int, Int?>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ---- 効果番号より前の共通指定 -----------------------------------
        SectionCard(title = "全ての効果に共通する指定") {
            Text(
                "ここに書いた【場所】【条件】【コスト】は、このカードの全ての効果の発動に必要になります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LocationPicker(
                kind = kind,
                selected = effect.locations,
                onChange = { onChange(effect.copy(locations = it)) }
            )

            ConditionList(
                conditions = effect.conditions,
                master = master,
                onAdd = { conditionTarget = COMMON },
                onRemove = { index ->
                    onChange(
                        effect.copy(
                            conditions = effect.conditions.toMutableList()
                                .also { it.removeAt(index) })
                    )
                }
            )

            CostList(
                costs = effect.costs,
                master = master,
                onAdd = { costTarget = COMMON },
                onRemove = { index ->
                    onChange(
                        effect.copy(
                            costs = effect.costs.toMutableList().also { it.removeAt(index) })
                    )
                }
            )
        }

        // ---- 各効果 -----------------------------------------------------
        effect.clauses.forEachIndexed { clauseIndex, clause ->
            ClauseEditor(
                index = clauseIndex,
                clause = clause,
                kind = kind,
                master = master,
                onChange = { updated ->
                    onChange(
                        effect.copy(
                            clauses = effect.clauses.toMutableList()
                                .also { it[clauseIndex] = updated })
                    )
                },
                onDelete = {
                    onChange(
                        effect.copy(
                            clauses = effect.clauses.toMutableList()
                                .also { it.removeAt(clauseIndex) })
                    )
                },
                onAddCondition = { conditionTarget = clauseIndex },
                onAddCost = { costTarget = clauseIndex },
                onAddAction = { actionTarget = clauseIndex to null },
                onEditAction = { actionIndex -> actionTarget = clauseIndex to actionIndex }
            )
        }

        OutlinedButton(
            onClick = { onChange(effect.copy(clauses = effect.clauses + EffectClause())) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("効果 ${EffectNumbers.circled(effect.clauses.size)} を追加")
        }
    }

    // ---- ダイアログ -------------------------------------------------------

    conditionTarget?.let { target ->
        ConditionDialog(
            master = master,
            onDismiss = { conditionTarget = null },
            onConfirm = { condition ->
                onChange(
                    if (target == COMMON) {
                        effect.copy(conditions = effect.conditions + condition)
                    } else {
                        effect.copy(
                            clauses = effect.clauses.toMutableList().also { list ->
                                list[target] =
                                    list[target].copy(conditions = list[target].conditions + condition)
                            }
                        )
                    }
                )
                conditionTarget = null
            }
        )
    }

    costTarget?.let { target ->
        CostDialog(
            master = master,
            onDismiss = { costTarget = null },
            onConfirm = { cost ->
                onChange(
                    if (target == COMMON) {
                        effect.copy(costs = effect.costs + cost)
                    } else {
                        effect.copy(
                            clauses = effect.clauses.toMutableList().also { list ->
                                list[target] = list[target].copy(costs = list[target].costs + cost)
                            }
                        )
                    }
                )
                costTarget = null
            }
        )
    }

    actionTarget?.let { (clauseIndex, actionIndex) ->
        val clause = effect.clauses.getOrNull(clauseIndex)
        ActionDialog(
            initial = actionIndex?.let { clause?.actions?.getOrNull(it) },
            master = master,
            onDismiss = { actionTarget = null },
            onConfirm = { action ->
                onChange(
                    effect.copy(
                        clauses = effect.clauses.toMutableList().also { list ->
                            val current = list[clauseIndex]
                            val actions = current.actions.toMutableList()
                            if (actionIndex == null) actions.add(action)
                            else actions[actionIndex] = action
                            list[clauseIndex] = current.copy(actions = actions)
                        }
                    )
                )
                actionTarget = null
            }
        )
    }
}

private const val COMMON = -1

@Composable
private fun ClauseEditor(
    index: Int,
    clause: EffectClause,
    kind: CardKind,
    master: MasterData,
    onChange: (EffectClause) -> Unit,
    onDelete: () -> Unit,
    onAddCondition: () -> Unit,
    onAddCost: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int) -> Unit
) {
    SectionCard(
        title = "効果 ${EffectNumbers.circled(index)}",
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "この効果を削除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        if (kind == CardKind.MONSTER) {
            Dropdown(
                label = "発動タイミング",
                items = EffectTiming.forMonster,
                selected = clause.timing,
                itemLabel = { it.label }
            ) { onChange(clause.copy(timing = it)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = clause.oncePerTurn,
                onCheckedChange = { onChange(clause.copy(oncePerTurn = it)) }
            )
            Text("1ターンに1度しか使用できない", style = MaterialTheme.typography.bodySmall)
        }

        LocationPicker(
            kind = kind,
            selected = clause.locations,
            label = "この効果だけの【場所】",
            onChange = { onChange(clause.copy(locations = it)) }
        )

        ConditionList(
            conditions = clause.conditions,
            master = master,
            label = "この効果だけの【条件】",
            onAdd = onAddCondition,
            onRemove = { i ->
                onChange(
                    clause.copy(
                        conditions = clause.conditions.toMutableList().also { it.removeAt(i) })
                )
            }
        )

        CostList(
            costs = clause.costs,
            master = master,
            label = "この効果だけの【コスト】",
            onAdd = onAddCost,
            onRemove = { i ->
                onChange(
                    clause.copy(costs = clause.costs.toMutableList().also { it.removeAt(i) })
                )
            }
        )

        HorizontalDivider()
        Text(
            "【効果】",
            style = MaterialTheme.typography.labelMedium,
            color = Gold,
            fontWeight = FontWeight.Bold
        )
        if (clause.actions.isEmpty()) {
            Text(
                "まだ効果がありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        clause.actions.forEachIndexed { actionIndex, action ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    EffectTextRenderer.actionToText(action, master) + "。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onEditAction(actionIndex) }
                )
                IconButton(onClick = {
                    onChange(
                        clause.copy(
                            actions = clause.actions.toMutableList()
                                .also { it.removeAt(actionIndex) })
                    )
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        OutlinedButton(onClick = onAddAction, modifier = Modifier.fillMaxWidth()) {
            Text("効果の文を追加")
        }
    }
}

@Composable
private fun LocationPicker(
    kind: CardKind,
    selected: List<ActivationLocation>,
    label: String = "【場所】発動できる場所",
    onChange: (List<ActivationLocation>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRowSimple {
            ActivationLocation.all.forEach { location ->
                Chip(location.label, selected = location in selected) {
                    onChange(
                        if (location in selected) selected - location else selected + location
                    )
                }
            }
        }
        if (selected.isEmpty()) {
            Text(
                if (kind == CardKind.MONSTER) "未指定のときはフィールドで発動します。"
                else "未指定のときは「フィールドで発動」になります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConditionList(
    conditions: List<Condition>,
    master: MasterData,
    label: String = "【条件】",
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        conditions.forEachIndexed { index, condition ->
            RemovableLine(EffectTextRenderer.conditionToText(condition, master)) { onRemove(index) }
        }
        Chip("＋ 条件を追加", onClick = onAdd)
    }
}

@Composable
private fun CostList(
    costs: List<Cost>,
    master: MasterData,
    label: String = "【コスト】",
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        costs.forEachIndexed { index, cost ->
            RemovableLine(EffectTextRenderer.costToText(cost, master)) { onRemove(index) }
        }
        Chip("＋ コストを追加", onClick = onAdd)
    }
}

@Composable
private fun RemovableLine(text: String, onRemove: () -> Unit) {
    Surface(color = Surface2, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
