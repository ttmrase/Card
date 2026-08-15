package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Surface2

// ===========================================================================
// 述語（効果）の編集
// ===========================================================================

private enum class ActionType(val label: String, val usesScope: Boolean) {
    DESTROY("破壊する", true),
    BANISH("除外する", true),
    TO_HAND("手札に加える", true),
    TO_GRAVE("墓地へ送る", true),
    TO_DECK("デッキに戻す", true),
    SPECIAL_SUMMON("特殊召喚する", true),
    MODIFY_STAT("攻撃力・守備力を変化させる", true),
    CHANGE_POSITION("表示形式を変更する", true),
    DRAW("ドローする", false),
    DAMAGE("ダメージを与える", false),
    RECOVER("ライフを回復する", false),
    DISCARD("手札を捨てさせる", false),
    MILL("デッキから墓地へ送る", false),
    NEGATE("発動を無効にし破壊する", false)
}

private fun typeOf(action: Action): ActionType = when (action) {
    is DestroyAction -> ActionType.DESTROY
    is BanishAction -> ActionType.BANISH
    is ToHandAction -> ActionType.TO_HAND
    is ToGraveAction -> ActionType.TO_GRAVE
    is ToDeckAction -> ActionType.TO_DECK
    is SpecialSummonAction -> ActionType.SPECIAL_SUMMON
    is ModifyStatAction -> ActionType.MODIFY_STAT
    is ChangePositionAction -> ActionType.CHANGE_POSITION
    is DrawAction -> ActionType.DRAW
    is DamageAction -> ActionType.DAMAGE
    is RecoverAction -> ActionType.RECOVER
    is DiscardAction -> ActionType.DISCARD
    is MillAction -> ActionType.MILL
    NegateAction -> ActionType.NEGATE
}

private fun scopeOf(action: Action): CardScope? = when (action) {
    is DestroyAction -> action.scope
    is BanishAction -> action.scope
    is ToHandAction -> action.scope
    is ToGraveAction -> action.scope
    is ToDeckAction -> action.scope
    is SpecialSummonAction -> action.scope
    is ModifyStatAction -> action.scope
    is ChangePositionAction -> action.scope
    else -> null
}

/**
 * 「主語・目的語・修飾語・述語」を選んで1つの効果を組み立てるダイアログ。
 */
@Composable
fun ActionDialog(
    initial: Action?,
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit
) {
    var type by remember { mutableStateOf(initial?.let(::typeOf) ?: ActionType.DESTROY) }
    var scope by remember {
        mutableStateOf(initial?.let(::scopeOf) ?: CardScope())
    }
    var who by remember {
        mutableStateOf(
            when (initial) {
                is DrawAction -> initial.who
                is DamageAction -> initial.who
                is RecoverAction -> initial.who
                is DiscardAction -> initial.who
                is MillAction -> initial.who
                else -> PlayerRef.SELF
            }
        )
    }
    var amount by remember {
        mutableIntStateOf(
            when (initial) {
                is DrawAction -> initial.count
                is DamageAction -> initial.amount
                is RecoverAction -> initial.amount
                is DiscardAction -> initial.count
                is MillAction -> initial.count
                else -> 1
            }
        )
    }
    var position by remember {
        mutableStateOf(
            when (initial) {
                is SpecialSummonAction -> initial.position
                is ChangePositionAction -> initial.position
                else -> Position.ATTACK
            }
        )
    }
    var summonController by remember {
        mutableStateOf((initial as? SpecialSummonAction)?.controller ?: PlayerRef.SELF)
    }
    var stat by remember {
        mutableStateOf((initial as? ModifyStatAction)?.stat ?: StatKind.ATK)
    }
    var delta by remember {
        mutableIntStateOf((initial as? ModifyStatAction)?.delta ?: 500)
    }
    var toBottom by remember {
        mutableStateOf((initial as? ToDeckAction)?.toBottom ?: false)
    }
    var randomDiscard by remember {
        mutableStateOf((initial as? DiscardAction)?.random ?: false)
    }

    fun build(): Action = when (type) {
        ActionType.DESTROY -> DestroyAction(scope)
        ActionType.BANISH -> BanishAction(scope)
        ActionType.TO_HAND -> ToHandAction(scope)
        ActionType.TO_GRAVE -> ToGraveAction(scope)
        ActionType.TO_DECK -> ToDeckAction(scope, toBottom)
        ActionType.SPECIAL_SUMMON -> SpecialSummonAction(scope, position, summonController)
        ActionType.MODIFY_STAT -> ModifyStatAction(scope, stat, delta)
        ActionType.CHANGE_POSITION -> ChangePositionAction(scope, position)
        ActionType.DRAW -> DrawAction(who, amount)
        ActionType.DAMAGE -> DamageAction(who, amount)
        ActionType.RECOVER -> RecoverAction(who, amount)
        ActionType.DISCARD -> DiscardAction(who, amount, randomDiscard)
        ActionType.MILL -> MillAction(who, amount)
        ActionType.NEGATE -> NegateAction
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "効果を追加" else "効果を編集") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown(
                    label = "述語（何をする）",
                    items = ActionType.entries.toList(),
                    selected = type,
                    itemLabel = { it.label },
                    onSelect = { type = it }
                )

                if (type.usesScope) {
                    HorizontalDivider()
                    CardScopeEditor(scope = scope, master = master) { scope = it }
                }

                when (type) {
                    ActionType.SPECIAL_SUMMON -> {
                        HorizontalDivider()
                        Dropdown("特殊召喚する側", PlayerRef.all, summonController, { it.label }) {
                            summonController = it
                        }
                        Dropdown("表示形式", Position.all, position, { it.label }) { position = it }
                    }

                    ActionType.CHANGE_POSITION ->
                        Dropdown("変更後の表示形式", Position.all, position, { it.label }) {
                            position = it
                        }

                    ActionType.MODIFY_STAT -> {
                        HorizontalDivider()
                        Dropdown("対象の数値", StatKind.all, stat, { it.label }) { stat = it }
                        NumberField("変化量（マイナスで下げる）", delta) { delta = it }
                    }

                    ActionType.TO_DECK -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = toBottom, onCheckedChange = { toBottom = it })
                        Text("デッキの一番下に戻す")
                    }

                    ActionType.DRAW, ActionType.MILL -> {
                        Dropdown("対象プレイヤー", PlayerRef.all, who, { it.label }) { who = it }
                        NumberField("枚数", amount) { amount = it.coerceIn(1, 20) }
                    }

                    ActionType.DAMAGE, ActionType.RECOVER -> {
                        Dropdown("対象プレイヤー", PlayerRef.all, who, { it.label }) { who = it }
                        NumberField("ポイント", amount) { amount = it.coerceAtLeast(0) }
                    }

                    ActionType.DISCARD -> {
                        Dropdown("対象プレイヤー", PlayerRef.all, who, { it.label }) { who = it }
                        NumberField("枚数", amount) { amount = it.coerceIn(1, 20) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = randomDiscard,
                                onCheckedChange = { randomDiscard = it })
                            Text("ランダムに捨てさせる")
                        }
                    }

                    ActionType.NEGATE -> Text(
                        "相手が発動したカードや、召喚・攻撃宣言に対して発動すると、" +
                            "それを無効にして破壊します。罠カードでの使用を想定しています。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> Unit
                }

                HorizontalDivider()
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Text(
                        EffectTextRenderer.actionToText(build(), master) + "。",
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

// ===========================================================================
// 条件
// ===========================================================================

private enum class ConditionType(val label: String) {
    EXISTS("特定のカードが存在する"),
    LIFE("ライフが一定値である"),
    ZONE_COUNT("領域の枚数が一定値である")
}

@Composable
fun ConditionDialog(
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (Condition) -> Unit
) {
    var type by remember { mutableStateOf(ConditionType.EXISTS) }
    var scope by remember { mutableStateOf(CardScope(who = PlayerRef.SELF, zone = ZoneType.FIELD)) }
    var atLeast by remember { mutableIntStateOf(1) }
    var negate by remember { mutableStateOf(false) }
    var who by remember { mutableStateOf(PlayerRef.SELF) }
    var cmp by remember { mutableStateOf(Cmp.LE) }
    var value by remember { mutableIntStateOf(2000) }
    var zone by remember { mutableStateOf(ZoneType.HAND) }

    fun build(): Condition = when (type) {
        ConditionType.EXISTS -> CardExistsCondition(scope, atLeast.coerceAtLeast(1), negate)
        ConditionType.LIFE -> LifeCondition(who, cmp, value)
        ConditionType.ZONE_COUNT -> ZoneCountCondition(who, zone, cmp, value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("【条件】を追加") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown(
                    "条件の種類", ConditionType.entries.toList(), type, { it.label }
                ) { type = it }

                when (type) {
                    ConditionType.EXISTS -> {
                        HorizontalDivider()
                        CardScopeEditor(scope, master) { scope = it }
                        NumberField("必要な数", atLeast) { atLeast = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = negate, onCheckedChange = { negate = it })
                            Text("逆に「存在しない」ことを条件にする")
                        }
                    }

                    ConditionType.LIFE -> {
                        Dropdown("対象", PlayerRef.all, who, { it.label }) { who = it }
                        NumberField("ライフ", value) { value = it }
                        Dropdown("比較", Cmp.all, cmp, { it.label }) { cmp = it }
                    }

                    ConditionType.ZONE_COUNT -> {
                        Dropdown("対象", PlayerRef.all, who, { it.label }) { who = it }
                        Dropdown("領域", ZoneType.all, zone, { it.label }) { zone = it }
                        NumberField("枚数", value) { value = it }
                        Dropdown("比較", Cmp.all, cmp, { it.label }) { cmp = it }
                    }
                }

                HorizontalDivider()
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Text(
                        EffectTextRenderer.conditionToText(build(), master),
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

// ===========================================================================
// コスト
// ===========================================================================

private enum class CostType(val label: String) {
    PAY_LIFE("ライフを払う"),
    DISCARD("手札を捨てる"),
    TRIBUTE("自分のモンスターをリリースする"),
    BANISH_GRAVE("自分の墓地のカードを除外する"),
    MILL("自分のデッキから墓地へ送る")
}

@Composable
fun CostDialog(
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (Cost) -> Unit
) {
    var type by remember { mutableStateOf(CostType.PAY_LIFE) }
    var amount by remember { mutableIntStateOf(500) }
    var count by remember { mutableIntStateOf(1) }
    var filters by remember { mutableStateOf(listOf<CardFilter>()) }
    var showFilterDialog by remember { mutableStateOf(false) }

    fun build(): Cost = when (type) {
        CostType.PAY_LIFE -> PayLifeCost(amount.coerceAtLeast(0))
        CostType.DISCARD -> DiscardCost(count.coerceAtLeast(1), filters)
        CostType.TRIBUTE -> TributeCost(count.coerceAtLeast(1), filters)
        CostType.BANISH_GRAVE -> BanishFromGraveCost(count.coerceAtLeast(1), filters)
        CostType.MILL -> MillCost(count.coerceAtLeast(1))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("【コスト】を追加") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown("コストの種類", CostType.entries.toList(), type, { it.label }) { type = it }

                if (type == CostType.PAY_LIFE) {
                    NumberField("支払うライフ", amount) { amount = it }
                } else {
                    NumberField("枚数／体数", count) { count = it }
                }

                if (type == CostType.DISCARD || type == CostType.TRIBUTE ||
                    type == CostType.BANISH_GRAVE
                ) {
                    Text(
                        "対象を限定する（任意）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRowSimple {
                        filters.forEachIndexed { index, filter ->
                            Chip(filterChipLabel(filter, master) + " ✕", selected = true) {
                                filters = filters.toMutableList().also { it.removeAt(index) }
                            }
                        }
                        Chip("＋ 条件を追加") { showFilterDialog = true }
                    }
                }

                HorizontalDivider()
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Text(
                        EffectTextRenderer.costToText(build(), master),
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

    if (showFilterDialog) {
        FilterDialog(
            master = master,
            onDismiss = { showFilterDialog = false },
            onConfirm = {
                filters = filters + it
                showFilterDialog = false
            }
        )
    }
}

// ===========================================================================
// 制限（発動回数）
// ===========================================================================

/**
 * 「1ターンにn度まで」の制限を作るダイアログ。
 * 数える単位は、このカード1枚・同名カード全体・カテゴリ単位から選ぶ。
 */
@Composable
fun LimitDialog(
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (UsageLimit) -> Unit
) {
    var scope by remember { mutableStateOf(LimitScope.THIS_CARD) }
    var times by remember { mutableIntStateOf(1) }
    var categoryId by remember { mutableStateOf<String?>(null) }

    fun build() = UsageLimit(
        scope = scope,
        times = times.coerceAtLeast(1),
        categoryId = if (scope == LimitScope.CATEGORY) categoryId else null
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("【制限】を追加") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown("数える単位", LimitScope.all, scope, { it.label }) { scope = it }
                NumberField("1ターンに発動できる回数", times) { times = it }

                if (scope == LimitScope.CATEGORY) {
                    Dropdown(
                        label = "対象のカテゴリ",
                        items = master.categories,
                        selected = master.categories.firstOrNull { it.id == categoryId },
                        itemLabel = { it.name },
                        placeholder = "このカードのカテゴリ"
                    ) { categoryId = it.id }
                    if (categoryId != null) {
                        TextButton(onClick = { categoryId = null }) {
                            Text("このカードのカテゴリを使う")
                        }
                    }
                }

                HorizontalDivider()
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Text(
                        EffectTextRenderer.limitToText(build(), master, cardWide = true) + "。",
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
