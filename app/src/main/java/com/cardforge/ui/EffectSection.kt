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
 * 編集する場所。
 *
 * [clauseIndex] が null ならカード共通の指定、[branchIndex] が非 null なら
 * その効果の場合分けの中、[itemIndex] が非 null なら既存の項目の編集。
 */
private data class EditSlot(
    val clauseIndex: Int? = null,
    val branchIndex: Int? = null,
    val itemIndex: Int? = null
)

/**
 * 効果テキストのエディタ。
 *
 * 上段が効果番号より前に書かれる共通の【場所】【条件】【コスト】【制限】、
 * 下段が ①②… の各効果。各効果は自前の指定と、場合分け（●）を持てる。
 * 追加済みの項目はタップすると編集できる。
 */
@Composable
fun EffectEditorSection(
    kind: CardKind,
    effect: EffectText,
    master: MasterData,
    /** 「トークンを特殊召喚する」で選べるトークンのカード。 */
    tokenCards: List<CardDef> = emptyList(),
    onChange: (EffectText) -> Unit
) {
    var conditionSlot by remember { mutableStateOf<EditSlot?>(null) }
    var costSlot by remember { mutableStateOf<EditSlot?>(null) }
    var limitSlot by remember { mutableStateOf<EditSlot?>(null) }
    var actionSlot by remember { mutableStateOf<EditSlot?>(null) }
    var lockSlot by remember { mutableStateOf<EditSlot?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ---- 効果番号より前の共通指定 -----------------------------------
        SectionCard(title = "全ての効果に共通する指定") {
            Text(
                "ここに書いた指定は、このカードの全ての効果の発動に必要になります。" +
                    "追加済みの項目はタップすると編集できます。",
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
                onAdd = { conditionSlot = EditSlot() },
                onEdit = { conditionSlot = EditSlot(itemIndex = it) },
                onRemove = { index ->
                    onChange(effect.copy(conditions = effect.conditions.removedAt(index)))
                }
            )

            CostList(
                costs = effect.costs,
                master = master,
                onAdd = { costSlot = EditSlot() },
                onEdit = { costSlot = EditSlot(itemIndex = it) },
                onRemove = { index ->
                    onChange(effect.copy(costs = effect.costs.removedAt(index)))
                }
            )

            LimitList(
                limits = effect.limits,
                master = master,
                label = "【制限】カード全体の発動回数",
                cardWide = true,
                onAdd = { limitSlot = EditSlot() },
                onEdit = { limitSlot = EditSlot(itemIndex = it) },
                onRemove = { index ->
                    onChange(effect.copy(limits = effect.limits.removedAt(index)))
                }
            )

            PlayLockList(
                locks = effect.playLocks,
                master = master,
                label = "【制限】発動に付く縛り（効果ではないので無効にされても残る）",
                onAdd = { lockSlot = EditSlot() },
                onEdit = { lockSlot = EditSlot(itemIndex = it) },
                onRemove = { index ->
                    onChange(effect.copy(playLocks = effect.playLocks.removedAt(index)))
                }
            )

            NoResponsePicker(
                selected = effect.noResponseFrom,
                label = "【制限】この発動に対して効果を発動できない側"
            ) { onChange(effect.copy(noResponseFrom = it)) }

            HorizontalDivider()
            Text(
                "【発動後】",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Dropdown(
                label = "",
                items = AfterActivation.all,
                selected = effect.afterActivation ?: EffectText.defaultAfterActivation(kind),
                itemLabel = { it.label }
            ) { onChange(effect.copy(afterActivation = it)) }
            Text(
                if (kind == CardKind.MONSTER)
                    "発動して解決したあと、このカードをどうするか。指定しない場合はそのまま残ります。"
                else
                    "発動して解決したあと、このカードをどうするか。" +
                        "指定しない場合は「墓地へ送る」になります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- 各効果 -----------------------------------------------------
        effect.clauses.forEachIndexed { clauseIndex, clause ->
            // あとから前に効果を差し込めるようにする。
            InsertClauseButton(clauseIndex) { onChange(effect.insertClause(clauseIndex)) }

            ClauseEditor(
                index = clauseIndex,
                clause = clause,
                kind = kind,
                master = master,
                onChange = { updated ->
                    onChange(effect.copy(clauses = effect.clauses.replacedAt(clauseIndex, updated)))
                },
                onDelete = { onChange(effect.removeClause(clauseIndex)) },
                slot = { branchIndex, itemIndex -> EditSlot(clauseIndex, branchIndex, itemIndex) },
                onConditionSlot = { conditionSlot = it },
                onCostSlot = { costSlot = it },
                onLimitSlot = { limitSlot = it },
                onActionSlot = { actionSlot = it },
                onLockSlot = { lockSlot = it }
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

    conditionSlot?.let { slot ->
        ConditionDialog(
            master = master,
            initial = slot.itemIndex?.let { effect.conditionsAt(slot).getOrNull(it) },
            onDismiss = { conditionSlot = null },
            onConfirm = { condition ->
                onChange(effect.withConditions(slot) { it.upsert(slot.itemIndex, condition) })
                conditionSlot = null
            }
        )
    }

    costSlot?.let { slot ->
        CostDialog(
            master = master,
            initial = slot.itemIndex?.let { effect.costsAt(slot).getOrNull(it) },
            onDismiss = { costSlot = null },
            onConfirm = { cost ->
                onChange(effect.withCosts(slot) { it.upsert(slot.itemIndex, cost) })
                costSlot = null
            }
        )
    }

    limitSlot?.let { slot ->
        LimitDialog(
            master = master,
            initial = slot.itemIndex?.let { effect.limitsAt(slot).getOrNull(it) },
            // 効果番号より前の制限だけ、掛ける効果を選べるようにする。
            clauseCount = if (slot.clauseIndex == null) effect.clauses.size else 0,
            onDismiss = { limitSlot = null },
            onConfirm = { limit ->
                onChange(effect.withLimits(slot) { it.upsert(slot.itemIndex, limit) })
                limitSlot = null
            }
        )
    }

    lockSlot?.let { slot ->
        PlayLockDialog(
            master = master,
            initial = slot.itemIndex?.let { effect.locksAt(slot).getOrNull(it) },
            onDismiss = { lockSlot = null },
            onConfirm = { lock ->
                onChange(effect.withLocks(slot) { it.upsert(slot.itemIndex, lock) })
                lockSlot = null
            }
        )
    }

    actionSlot?.let { slot ->
        ActionDialog(
            initial = slot.itemIndex?.let { effect.actionsAt(slot).getOrNull(it) },
            master = master,
            tokenCards = tokenCards,
            onDismiss = { actionSlot = null },
            onConfirm = { action ->
                onChange(effect.withActions(slot) { it.upsert(slot.itemIndex, action) })
                actionSlot = null
            }
        )
    }
}

// ---------------------------------------------------------------------------
// リストの読み書き
// ---------------------------------------------------------------------------

private fun <T> List<T>.removedAt(index: Int): List<T> =
    toMutableList().also { it.removeAt(index) }

/**
 * [at] の位置に空の効果を差し込む。
 * 【制限】が名指ししている効果番号もあわせてずらす。
 */
private fun EffectText.insertClause(at: Int): EffectText = copy(
    clauses = clauses.toMutableList().also { it.add(at, EffectClause()) },
    limits = limits.map { limit ->
        limit.copy(clauseIndices = limit.clauseIndices.map { if (it >= at) it + 1 else it })
    }
)

/** [at] の効果を消す。【制限】が名指ししている効果番号もあわせて詰める。 */
private fun EffectText.removeClause(at: Int): EffectText = copy(
    clauses = clauses.removedAt(at),
    limits = limits.map { limit ->
        limit.copy(
            clauseIndices = limit.clauseIndices
                .filter { it != at }
                .map { if (it > at) it - 1 else it }
        )
    }
)

/** 任意にする処理の番号を入れ替える。 */
private fun List<Int>.toggled(index: Int): List<Int> =
    if (index in this) this - index else this + index

/** [removed] 番目の処理を消したあとの、任意にする処理の番号。 */
private fun List<Int>.shiftedAfterRemoval(removed: Int): List<Int> =
    filter { it != removed }.map { if (it > removed) it - 1 else it }

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

/** [index] が null なら末尾に追加、そうでなければ差し替える。 */
private fun <T> List<T>.upsert(index: Int?, value: T): List<T> =
    if (index == null) this + value else replacedAt(index, value)

private fun EffectText.conditionsAt(slot: EditSlot): List<Condition> = when {
    slot.clauseIndex == null -> conditions
    slot.branchIndex == null -> clauses[slot.clauseIndex].conditions
    else -> clauses[slot.clauseIndex].branches[slot.branchIndex].conditions
}

private fun EffectText.costsAt(slot: EditSlot): List<Cost> =
    if (slot.clauseIndex == null) costs else clauses[slot.clauseIndex].costs

private fun EffectText.limitsAt(slot: EditSlot): List<UsageLimit> =
    if (slot.clauseIndex == null) limits else clauses[slot.clauseIndex].limits

private fun EffectText.locksAt(slot: EditSlot): List<PlayLock> =
    if (slot.clauseIndex == null) playLocks else clauses[slot.clauseIndex].playLocks

private fun EffectText.actionsAt(slot: EditSlot): List<Action> = when {
    slot.clauseIndex == null -> emptyList()
    slot.branchIndex == null -> clauses[slot.clauseIndex].actions
    else -> clauses[slot.clauseIndex].branches[slot.branchIndex].actions
}

private fun EffectText.withConditions(
    slot: EditSlot,
    block: (List<Condition>) -> List<Condition>
): EffectText = when {
    slot.clauseIndex == null -> copy(conditions = block(conditions))
    slot.branchIndex == null -> updateClause(slot.clauseIndex) {
        it.copy(conditions = block(it.conditions))
    }

    else -> updateClause(slot.clauseIndex) { clause ->
        clause.copy(
            branches = clause.branches.replacedAt(slot.branchIndex) { branch ->
                branch.copy(conditions = block(branch.conditions))
            }
        )
    }
}

private fun EffectText.withCosts(slot: EditSlot, block: (List<Cost>) -> List<Cost>): EffectText =
    if (slot.clauseIndex == null) copy(costs = block(costs))
    else updateClause(slot.clauseIndex) { it.copy(costs = block(it.costs)) }

private fun EffectText.withLimits(
    slot: EditSlot,
    block: (List<UsageLimit>) -> List<UsageLimit>
): EffectText =
    if (slot.clauseIndex == null) copy(limits = block(limits))
    else updateClause(slot.clauseIndex) { it.copy(limits = block(it.limits)) }

private fun EffectText.withLocks(
    slot: EditSlot,
    block: (List<PlayLock>) -> List<PlayLock>
): EffectText =
    if (slot.clauseIndex == null) copy(playLocks = block(playLocks))
    else updateClause(slot.clauseIndex) { it.copy(playLocks = block(it.playLocks)) }

private fun EffectText.withActions(
    slot: EditSlot,
    block: (List<Action>) -> List<Action>
): EffectText {
    val clauseIndex = slot.clauseIndex ?: return this
    return if (slot.branchIndex == null) {
        updateClause(clauseIndex) { it.copy(actions = block(it.actions)) }
    } else {
        updateClause(clauseIndex) { clause ->
            clause.copy(
                branches = clause.branches.replacedAt(slot.branchIndex) { branch ->
                    branch.copy(actions = block(branch.actions))
                }
            )
        }
    }
}

private fun EffectText.updateClause(index: Int, block: (EffectClause) -> EffectClause): EffectText =
    copy(clauses = clauses.replacedAt(index, block(clauses[index])))

private fun <T> List<T>.replacedAt(index: Int, block: (T) -> T): List<T> =
    replacedAt(index, block(this[index]))

// ---------------------------------------------------------------------------
// 効果1つ分のエディタ
// ---------------------------------------------------------------------------

@Composable
private fun ClauseEditor(
    index: Int,
    clause: EffectClause,
    kind: CardKind,
    master: MasterData,
    onChange: (EffectClause) -> Unit,
    onDelete: () -> Unit,
    slot: (branchIndex: Int?, itemIndex: Int?) -> EditSlot,
    onConditionSlot: (EditSlot) -> Unit,
    onCostSlot: (EditSlot) -> Unit,
    onLimitSlot: (EditSlot) -> Unit,
    onActionSlot: (EditSlot) -> Unit,
    onLockSlot: (EditSlot) -> Unit
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
        Dropdown(
            label = "発動タイプ",
            items = ActivationMode.all,
            selected = clause.mode,
            itemLabel = { it.label }
        ) { onChange(clause.copy(mode = it)) }
        Text(
            "「発動時」はこのカード自体を発動したときにだけ処理されます。" +
                "「永続」は発動せず、このカードが【場所】にある限り適用されます。\n" +
                "【条件】に「〜した場合」を入れると、その出来事で発動する効果になります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = clause.quick,
                onCheckedChange = { onChange(clause.copy(quick = it)) }
            )
            Text("誘発即時（相手のターンや、相手の行動への割り込みでも発動できる）")
        }
        Text(
            "罠カードは、チェックを入れなくても割り込めます。" +
                "【条件】に「〜した場合」を書いた効果は、チェックが無くても" +
                "その出来事が起きた瞬間に発動します（手札誘発はこの形です）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
            onAdd = { onConditionSlot(slot(null, null)) },
            onEdit = { onConditionSlot(slot(null, it)) },
            onRemove = { onChange(clause.copy(conditions = clause.conditions.removedAt(it))) }
        )

        CostList(
            costs = clause.costs,
            master = master,
            label = "この効果だけの【コスト】",
            onAdd = { onCostSlot(slot(null, null)) },
            onEdit = { onCostSlot(slot(null, it)) },
            onRemove = { onChange(clause.copy(costs = clause.costs.removedAt(it))) }
        )

        LimitList(
            limits = clause.limits,
            master = master,
            label = "この効果だけの【制限】",
            cardWide = false,
            onAdd = { onLimitSlot(slot(null, null)) },
            onEdit = { onLimitSlot(slot(null, it)) },
            onRemove = { onChange(clause.copy(limits = clause.limits.removedAt(it))) }
        )

        PlayLockList(
            locks = clause.playLocks,
            master = master,
            label = "この効果だけの【制限】発動に付く縛り",
            onAdd = { onLockSlot(slot(null, null)) },
            onEdit = { onLockSlot(slot(null, it)) },
            onRemove = { onChange(clause.copy(playLocks = clause.playLocks.removedAt(it))) }
        )

        NoResponsePicker(
            selected = clause.noResponseFrom,
            label = "この効果だけの【制限】この発動に対して効果を発動できない側"
        ) { onChange(clause.copy(noResponseFrom = it)) }

        Text(
            "この効果だけの【発動後】",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Dropdown(
            label = "",
            items = AfterActivation.all,
            selected = clause.afterActivation,
            itemLabel = { it.label },
            placeholder = "カード共通の指定に従う"
        ) { onChange(clause.copy(afterActivation = it)) }
        if (clause.afterActivation != null) {
            TextButton(onClick = { onChange(clause.copy(afterActivation = null)) }) {
                Text("指定を解除")
            }
        }

        HorizontalDivider()
        ActionList(
            actions = clause.actions,
            optionalSteps = clause.optionalSteps,
            linkedSteps = clause.linkedSteps,
            master = master,
            label = "【効果】",
            onAdd = { onActionSlot(slot(null, null)) },
            onEdit = { onActionSlot(slot(null, it)) },
            onRemove = {
                onChange(
                    clause.copy(
                        actions = clause.actions.removedAt(it),
                        optionalSteps = clause.optionalSteps.shiftedAfterRemoval(it),
                        linkedSteps = clause.linkedSteps.shiftedAfterRemoval(it)
                    )
                )
            },
            onToggleOptional = {
                onChange(clause.copy(optionalSteps = clause.optionalSteps.toggled(it)))
            },
            onToggleLinked = {
                onChange(clause.copy(linkedSteps = clause.linkedSteps.toggled(it)))
            }
        )

        // ---- 場合分け ---------------------------------------------------
        HorizontalDivider()
        Text(
            "場合分け（●）",
            style = MaterialTheme.typography.labelMedium,
            color = Gold,
            fontWeight = FontWeight.Bold
        )
        Text(
            "条件ごとに違う処理をしたいときに使います。" +
                "「送られた場所によって」のような効果が作れます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (clause.branches.isNotEmpty()) {
            Dropdown(
                label = "当てはまるものの扱い",
                items = BranchMode.all,
                selected = clause.branchMode,
                itemLabel = { it.label }
            ) { onChange(clause.copy(branchMode = it)) }
        }

        clause.branches.forEachIndexed { branchIndex, branch ->
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
                            "● ${branchIndex + 1}つ目",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            onChange(clause.copy(branches = clause.branches.removedAt(branchIndex)))
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "この場合分けを削除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    ConditionList(
                        conditions = branch.conditions,
                        master = master,
                        label = "この場合の【条件】",
                        onAdd = { onConditionSlot(slot(branchIndex, null)) },
                        onEdit = { onConditionSlot(slot(branchIndex, it)) },
                        onRemove = { conditionIndex ->
                            onChange(
                                clause.copy(
                                    branches = clause.branches.replacedAt(branchIndex) {
                                        it.copy(conditions = it.conditions.removedAt(conditionIndex))
                                    }
                                )
                            )
                        }
                    )

                    ActionList(
                        actions = branch.actions,
                        optionalSteps = branch.optionalSteps,
                        linkedSteps = branch.linkedSteps,
                        master = master,
                        label = "この場合の【効果】",
                        onAdd = { onActionSlot(slot(branchIndex, null)) },
                        onEdit = { onActionSlot(slot(branchIndex, it)) },
                        onRemove = { actionIndex ->
                            onChange(
                                clause.copy(
                                    branches = clause.branches.replacedAt(branchIndex) {
                                        it.copy(
                                            actions = it.actions.removedAt(actionIndex),
                                            optionalSteps =
                                                it.optionalSteps.shiftedAfterRemoval(actionIndex),
                                            linkedSteps =
                                                it.linkedSteps.shiftedAfterRemoval(actionIndex)
                                        )
                                    }
                                )
                            )
                        },
                        onToggleOptional = { actionIndex ->
                            onChange(
                                clause.copy(
                                    branches = clause.branches.replacedAt(branchIndex) {
                                        it.copy(optionalSteps = it.optionalSteps.toggled(actionIndex))
                                    }
                                )
                            )
                        },
                        onToggleLinked = { actionIndex ->
                            onChange(
                                clause.copy(
                                    branches = clause.branches.replacedAt(branchIndex) {
                                        it.copy(linkedSteps = it.linkedSteps.toggled(actionIndex))
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        Chip("＋ 場合分けを追加") {
            onChange(clause.copy(branches = clause.branches + EffectBranch()))
        }
    }
}

// ---------------------------------------------------------------------------
// 一覧の部品
// ---------------------------------------------------------------------------

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
                "未指定のときは「フィールドで発動」になります。",
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
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    EditableList(
        label = label,
        lines = conditions.map { EffectTextRenderer.conditionToText(it, master) },
        addLabel = "＋ 条件を追加",
        onAdd = onAdd,
        onEdit = onEdit,
        onRemove = onRemove
    )
}

@Composable
private fun CostList(
    costs: List<Cost>,
    master: MasterData,
    label: String = "【コスト】",
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    EditableList(
        label = label,
        lines = costs.map { EffectTextRenderer.costToText(it, master) },
        addLabel = "＋ コストを追加",
        onAdd = onAdd,
        onEdit = onEdit,
        onRemove = onRemove
    )
}

@Composable
private fun LimitList(
    limits: List<UsageLimit>,
    master: MasterData,
    label: String,
    cardWide: Boolean,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    EditableList(
        label = label,
        lines = limits.map { EffectTextRenderer.limitToText(it, master, cardWide) },
        addLabel = "＋ 制限を追加",
        onAdd = onAdd,
        onEdit = onEdit,
        onRemove = onRemove
    )
}

@Composable
private fun PlayLockList(
    locks: List<PlayLock>,
    master: MasterData,
    label: String,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    EditableList(
        label = label,
        lines = locks.map { EffectTextRenderer.playLockToText(it, master) },
        addLabel = "＋ 制限を追加",
        onAdd = onAdd,
        onEdit = onEdit,
        onRemove = onRemove
    )
}

@Composable
private fun ActionList(
    actions: List<Action>,
    optionalSteps: List<Int>,
    linkedSteps: List<Int>,
    master: MasterData,
    label: String,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onToggleOptional: (Int) -> Unit,
    onToggleLinked: (Int) -> Unit
) {
    val units = stepUnits(actions.size, optionalSteps, linkedSteps)
    val unitOf = HashMap<Int, StepUnit>()
    units.forEach { unit -> unit.indices.forEach { unitOf[it] = unit } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EditableList(
            label = label,
            lines = actions.mapIndexed { index, action ->
                val text = EffectTextRenderer.actionToText(action, master)
                val unit = unitOf[index]
                val head = if (index > 0 && index in linkedSteps) "（前の処理と一体）" else ""
                val tail = when {
                    unit != null && unit.optional && index == unit.endInclusive ->
                        EffectTextRenderer.optionalStepText("") + "。"

                    else -> "。"
                }
                head + text + tail
            },
            addLabel = "＋ 効果の文を追加",
            emptyHint = "まだ効果がありません。",
            onAdd = onAdd,
            onEdit = onEdit,
            onRemove = onRemove,
            rowTrailing = { index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        Chip(
                            if (index in linkedSteps) "一体" else "別",
                            selected = index in linkedSteps
                        ) { onToggleLinked(index) }
                    }
                    val unit = unitOf[index]
                    if (unit == null || index == unit.start) {
                        Chip(
                            if (index in optionalSteps) "任意" else "強制",
                            selected = index in optionalSteps
                        ) { onToggleOptional(index) }
                    }
                }
            }
        )
        if (actions.size > 1) {
            Text(
                "「一体」にした処理は前の処理とまとめて扱われ、" +
                    "まとまりの先頭を「任意」にすると、まとめて行うかどうかを一度だけ選びます。" +
                    "「手札を見せ、デッキから墓地へ送ることができる」のように、" +
                    "片方だけを行えない処理を作れます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** タップで編集、ゴミ箱で削除できる一覧。 */
@Composable
private fun EditableList(
    label: String,
    lines: List<String>,
    addLabel: String,
    emptyHint: String? = null,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    /** 行ごとに出す追加のボタン。任意／強制の切り替えなどに使う。 */
    rowTrailing: (@Composable (Int) -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (lines.isEmpty() && emptyHint != null) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        lines.forEachIndexed { index, line ->
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
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEdit(index) }
                            .padding(vertical = 10.dp)
                    )
                    rowTrailing?.invoke(index)
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "削除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Chip(addLabel, onClick = onAdd)
    }
}

/** 「この効果の発動に対して〜はカードの効果を発動できない」の指定。 */
@Composable
private fun NoResponsePicker(
    selected: PlayerRef?,
    label: String,
    onChange: (PlayerRef?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRowSimple {
            Chip("制限なし", selected = selected == null) { onChange(null) }
            PlayerRef.all.forEach { candidate ->
                Chip(candidate.label, selected = selected == candidate) { onChange(candidate) }
            }
        }
        if (selected != null) {
            Text(
                EffectTextRenderer.noResponseToText(selected) + "。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 効果と効果のあいだに、新しい効果を差し込むボタン。 */
@Composable
private fun InsertClauseButton(index: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("ここに効果 ${EffectNumbers.circled(index)} を差し込む")
    }
}
