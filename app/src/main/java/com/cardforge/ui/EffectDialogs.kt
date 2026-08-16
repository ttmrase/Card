package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.game.EffectNumbers
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
    SET_SPELL_TRAP("魔法・罠ゾーンにセットする", true),
    PLACE_SPELL_TRAP("魔法・罠ゾーンに表側で置く", true),
    ACTIVATE_CARD("そのカードを発動する", true),
    GRANT_PROTECTION("耐性を与える（永続向き）", true),
    PREVENT_ATTACK("攻撃できなくする（永続向き）", true),
    RESTRICT_SUMMON("召喚・特殊召喚を制限する", false),
    REVEAL("カードを相手に見せる（公開する）", true),
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
    is SetSpellTrapAction -> ActionType.SET_SPELL_TRAP
    is PlaceSpellTrapAction -> ActionType.PLACE_SPELL_TRAP
    is ActivateCardAction -> ActionType.ACTIVATE_CARD
    is GrantProtectionAction -> ActionType.GRANT_PROTECTION
    is PreventAttackAction -> ActionType.PREVENT_ATTACK
    is RestrictSummonAction -> ActionType.RESTRICT_SUMMON
    is RevealAction -> ActionType.REVEAL
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
    is SetSpellTrapAction -> action.scope
    is PlaceSpellTrapAction -> action.scope
    is ActivateCardAction -> action.scope
    is GrantProtectionAction -> action.scope
    is PreventAttackAction -> action.scope
    is RevealAction -> action.scope
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
        mutableStateOf(
            initial?.let(::scopeOf)
                ?: if (initial is RevealAction) RevealAction().scope else CardScope()
        )
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
    var summonPositions by remember {
        mutableStateOf((initial as? SpecialSummonAction)?.choices ?: listOf(Position.ATTACK))
    }
    var stat by remember {
        mutableStateOf((initial as? ModifyStatAction)?.stat ?: StatKind.ATK)
    }
    var statValue by remember {
        mutableStateOf((initial as? ModifyStatAction)?.amountSpec ?: FixedValue(500))
    }
    var amountValue by remember {
        mutableStateOf(
            when (initial) {
                is DamageAction -> initial.amountSpec
                is RecoverAction -> initial.amountSpec
                else -> FixedValue(500)
            }
        )
    }
    var toBottom by remember {
        mutableStateOf((initial as? ToDeckAction)?.toBottom ?: false)
    }
    var randomDiscard by remember {
        mutableStateOf((initial as? DiscardAction)?.random ?: false)
    }
    var protection by remember {
        mutableStateOf((initial as? GrantProtectionAction)?.kind ?: ProtectionKind.OPPONENT_EFFECTS)
    }
    var summonKind by remember {
        mutableStateOf((initial as? RestrictSummonAction)?.summon ?: SummonKind.SPECIAL)
    }
    var restrictFilters by remember {
        mutableStateOf((initial as? RestrictSummonAction)?.filters ?: emptyList())
    }
    var restrictExcept by remember {
        mutableStateOf((initial as? RestrictSummonAction)?.except ?: true)
    }
    var restrictFromActivation by remember {
        mutableStateOf((initial as? RestrictSummonAction)?.fromActivation ?: true)
    }
    var showRestrictFilter by remember { mutableStateOf(false) }
    var revealDuration by remember {
        mutableStateOf(
            (initial as? RevealAction)?.duration ?: RevealDuration.MOMENT
        )
    }

    fun build(): Action = when (type) {
        ActionType.DESTROY -> DestroyAction(scope)
        ActionType.BANISH -> BanishAction(scope)
        ActionType.TO_HAND -> ToHandAction(scope)
        ActionType.TO_GRAVE -> ToGraveAction(scope)
        ActionType.TO_DECK -> ToDeckAction(scope, toBottom)
        ActionType.SPECIAL_SUMMON -> SpecialSummonAction(
            scope = scope,
            position = summonPositions.firstOrNull() ?: Position.ATTACK,
            controller = summonController,
            positionChoices = summonPositions
        )
        ActionType.MODIFY_STAT -> ModifyStatAction(scope, stat, deltaValue = statValue)
        ActionType.CHANGE_POSITION -> ChangePositionAction(scope, position)
        ActionType.DRAW -> DrawAction(who, amount)
        ActionType.DAMAGE -> DamageAction(who, amountValue = amountValue)
        ActionType.RECOVER -> RecoverAction(who, amountValue = amountValue)
        ActionType.DISCARD -> DiscardAction(who, amount, randomDiscard)
        ActionType.MILL -> MillAction(who, amount)
        ActionType.SET_SPELL_TRAP -> SetSpellTrapAction(scope)
        ActionType.PLACE_SPELL_TRAP -> PlaceSpellTrapAction(scope)
        ActionType.ACTIVATE_CARD -> ActivateCardAction(scope)
        ActionType.GRANT_PROTECTION -> GrantProtectionAction(scope, protection)
        ActionType.PREVENT_ATTACK -> PreventAttackAction(scope)
        ActionType.RESTRICT_SUMMON -> RestrictSummonAction(
            who = who,
            summon = summonKind,
            filters = restrictFilters,
            except = restrictExcept,
            fromActivation = restrictFromActivation
        )
        ActionType.REVEAL -> RevealAction(scope, revealDuration)
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
                        Text(
                            "選べる表示形式",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            Position.all.forEach { candidate ->
                                Chip(candidate.label, selected = candidate in summonPositions) {
                                    summonPositions =
                                        if (candidate in summonPositions) {
                                            (summonPositions - candidate)
                                                .ifEmpty { listOf(candidate) }
                                        } else {
                                            summonPositions + candidate
                                        }
                                }
                            }
                        }
                        Text(
                            if (summonPositions.size > 1)
                                "2つ以上選ぶと、効果を処理するときにプレイヤーが選びます。"
                            else "1つだけ選ぶと、その表示形式で固定されます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ActionType.CHANGE_POSITION ->
                        Dropdown("変更後の表示形式", Position.all, position, { it.label }) {
                            position = it
                        }

                    ActionType.MODIFY_STAT -> {
                        HorizontalDivider()
                        Dropdown("対象の数値", StatKind.all, stat, { it.label }) { stat = it }
                        ValueSpecEditor(
                            label = "変化量",
                            spec = statValue,
                            master = master,
                            allowNegative = true
                        ) { statValue = it }
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
                        ValueSpecEditor("ポイント", amountValue, master) { amountValue = it }
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

                    ActionType.GRANT_PROTECTION -> {
                        Dropdown("与える耐性", ProtectionKind.all, protection, { it.label }) {
                            protection = it
                        }
                        Text(
                            "【発動タイプ】を「永続」にすると常に適用され、" +
                                "発動する効果に書くとそのターンの間だけ適用されます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ActionType.PREVENT_ATTACK -> Text(
                        "【発動タイプ】を「永続」にすると常に適用され、" +
                            "発動する効果に書くとそのターンの間だけ適用されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ActionType.PLACE_SPELL_TRAP -> Text(
                        "発動はしないので、そのカードの永続の効果だけが働きます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ActionType.RESTRICT_SUMMON -> {
                        HorizontalDivider()
                        Dropdown("制限を受ける側", PlayerRef.all, who, { it.label }) { who = it }
                        Dropdown("制限する召喚", SummonKind.all, summonKind, { it.label }) {
                            summonKind = it
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = restrictExcept,
                                onCheckedChange = { restrictExcept = it }
                            )
                            Text("指定したカード「以外」を出せなくする")
                        }
                        Text(
                            "出せなくするカードの条件（空ならモンスター全体）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            restrictFilters.forEachIndexed { index, filter ->
                                Chip(filterChipLabel(filter, master) + " ✕", selected = true) {
                                    restrictFilters = restrictFilters.toMutableList()
                                        .also { it.removeAt(index) }
                                }
                            }
                            Chip("＋ 条件を追加") { showRestrictFilter = true }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = restrictFromActivation,
                                onCheckedChange = { restrictFromActivation = it }
                            )
                            Text("「このカードを発動するターン」と書く")
                        }
                        Text(
                            "制限はこのターンの間だけ続きます。" +
                                "【発動タイプ】を「発動時」にすると、" +
                                "効果を使うかどうかに関わらず発動しただけで掛かります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ActionType.REVEAL -> {
                        HorizontalDivider()
                        Dropdown(
                            "見せ方", RevealDuration.all, revealDuration, { it.label }
                        ) { revealDuration = it }
                        Text(
                            "「その場だけ」は見せて終わり、それ以外は指定の間、" +
                                "相手の画面にも中身が出たままになります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

    if (showRestrictFilter) {
        FilterDialog(
            master = master,
            onDismiss = { showRestrictFilter = false },
            onConfirm = {
                restrictFilters = restrictFilters + it
                showRestrictFilter = false
            }
        )
    }
}

// ===========================================================================
// 条件
// ===========================================================================

private enum class ConditionType(val label: String) {
    EVENT("〜した場合（出来事で発動する）"),
    SELF_ZONE("このカードが〇〇にある"),
    PHASE("〇〇フェイズである"),
    EXISTS("特定のカードが存在する"),
    LIFE("ライフが一定値である"),
    ZONE_COUNT("領域の枚数が一定値である")
}

@Composable
fun ConditionDialog(
    master: MasterData,
    initial: Condition? = null,
    onDismiss: () -> Unit,
    onConfirm: (Condition) -> Unit
) {
    var type by remember {
        mutableStateOf(
            when (initial) {
                is EventCondition -> ConditionType.EVENT
                is SelfZoneCondition -> ConditionType.SELF_ZONE
                is PhaseCondition -> ConditionType.PHASE
                is CardExistsCondition -> ConditionType.EXISTS
                is LifeCondition -> ConditionType.LIFE
                is ZoneCountCondition -> ConditionType.ZONE_COUNT
                null -> ConditionType.EVENT
            }
        )
    }
    var scope by remember {
        mutableStateOf(
            (initial as? CardExistsCondition)?.scope
                ?: CardScope(who = PlayerRef.SELF, zone = ZoneType.FIELD)
        )
    }
    var event by remember {
        mutableStateOf((initial as? EventCondition)?.event ?: GameEventType.SUMMONED)
    }
    var eventWho by remember {
        mutableStateOf((initial as? EventCondition)?.who ?: PlayerRef.OPPONENT)
    }
    var eventSelfOnly by remember {
        mutableStateOf((initial as? EventCondition)?.selfOnly ?: false)
    }
    var eventFilters by remember {
        mutableStateOf((initial as? EventCondition)?.filters ?: emptyList())
    }
    var eventCause by remember {
        mutableStateOf((initial as? EventCondition)?.cause ?: CauseFilter.ANY)
    }
    var showEventFilter by remember { mutableStateOf(false) }
    var atLeast by remember { mutableIntStateOf((initial as? CardExistsCondition)?.atLeast ?: 1) }
    var negate by remember { mutableStateOf((initial as? CardExistsCondition)?.negate ?: false) }
    var who by remember {
        mutableStateOf(
            when (initial) {
                is LifeCondition -> initial.who
                is ZoneCountCondition -> initial.who
                else -> PlayerRef.SELF
            }
        )
    }
    var cmp by remember {
        mutableStateOf(
            when (initial) {
                is LifeCondition -> initial.cmp
                is ZoneCountCondition -> initial.cmp
                else -> Cmp.LE
            }
        )
    }
    var value by remember {
        mutableIntStateOf(
            when (initial) {
                is LifeCondition -> initial.value
                is ZoneCountCondition -> initial.value
                else -> 2000
            }
        )
    }
    var zone by remember {
        mutableStateOf((initial as? ZoneCountCondition)?.zone ?: ZoneType.HAND)
    }
    var phases by remember { mutableStateOf((initial as? PhaseCondition)?.phases ?: emptyList()) }
    var selfZones by remember {
        mutableStateOf((initial as? SelfZoneCondition)?.zones ?: emptyList())
    }

    fun build(): Condition = when (type) {
        ConditionType.EVENT -> EventCondition(
            event = event,
            who = eventWho,
            selfOnly = eventSelfOnly,
            filters = if (eventSelfOnly) emptyList() else eventFilters,
            cause = eventCause
        )

        ConditionType.SELF_ZONE -> SelfZoneCondition(selfZones)
        ConditionType.PHASE -> PhaseCondition(phases)
        ConditionType.EXISTS -> CardExistsCondition(scope, atLeast.coerceAtLeast(1), negate)
        ConditionType.LIFE -> LifeCondition(who, cmp, value)
        ConditionType.ZONE_COUNT -> ZoneCountCondition(who, zone, cmp, value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "【条件】を追加" else "【条件】を編集") },
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
                    ConditionType.EVENT -> {
                        HorizontalDivider()
                        Text(
                            "この条件を付けると、その出来事が起きたときに発動する効果になります。" +
                                "付けない効果は、自分のメインフェイズに手動で発動する効果です。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Dropdown("出来事", GameEventType.all, event, { it.label }) { event = it }
                        Dropdown(
                            "何が原因で起きたか",
                            CauseFilter.all,
                            eventCause,
                            { it.label }
                        ) { eventCause = it }
                        Text(
                            "「相手・自分」は、この効果を持つカードの持ち主から見た呼び方です。" +
                                "コストの支払いも、そのカードを発動した側の効果として扱います。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = eventSelfOnly,
                                onCheckedChange = { eventSelfOnly = it }
                            )
                            Text("このカード自身が対象のときだけ")
                        }
                        if (!eventSelfOnly) {
                            Dropdown("誰の側の出来事か", PlayerRef.all, eventWho, { it.label }) {
                                eventWho = it
                            }
                            if (!event.isPlayerEvent) {
                                Text(
                                    "対象のカードを限定する（任意）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRowSimple {
                                    eventFilters.forEachIndexed { index, filter ->
                                        Chip(
                                            filterChipLabel(filter, master) + " ✕",
                                            selected = true
                                        ) {
                                            eventFilters = eventFilters.toMutableList()
                                                .also { it.removeAt(index) }
                                        }
                                    }
                                    Chip("＋ 条件を追加") { showEventFilter = true }
                                }
                            }
                        }
                    }

                    ConditionType.SELF_ZONE -> {
                        HorizontalDivider()
                        Text(
                            "この効果を持つカード自身がどこにあるかを見ます。" +
                                "「送られた場所によって」のような場合分けに使います。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            ZoneType.all.forEach { candidate ->
                                Chip(candidate.label, selected = candidate in selfZones) {
                                    selfZones =
                                        if (candidate in selfZones) selfZones - candidate
                                        else selfZones + candidate
                                }
                            }
                        }
                    }

                    ConditionType.PHASE -> {
                        HorizontalDivider()
                        Text(
                            "指定したフェイズにだけ発動できるようになります。" +
                                "エンドフェイズのように操作できないフェイズでも、" +
                                "そのときに発動するか確認が出ます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            Phase.all.forEach { candidate ->
                                Chip(candidate.label, selected = candidate in phases) {
                                    phases =
                                        if (candidate in phases) phases - candidate
                                        else phases + candidate
                                }
                            }
                        }
                    }

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

    if (showEventFilter) {
        FilterDialog(
            master = master,
            onDismiss = { showEventFilter = false },
            onConfirm = {
                eventFilters = eventFilters + it
                showEventFilter = false
            }
        )
    }
}

// ===========================================================================
// コスト
// ===========================================================================

private enum class CostType(val label: String) {
    MOVE("カードを動かす（対象を自由に指定）"),
    PAY_LIFE("ライフを払う"),
    SELF_TO_GRAVE("このカードを墓地へ送る"),
    SELF_BANISH("このカードを除外する"),
    MILL("自分のデッキから墓地へ送る"),
    REVEAL("カードを相手に見せる（公開する）")
}

@Composable
fun CostDialog(
    master: MasterData,
    initial: Cost? = null,
    onDismiss: () -> Unit,
    onConfirm: (Cost) -> Unit
) {
    var type by remember {
        mutableStateOf(
            when (initial) {
                is MoveCost -> CostType.MOVE
                is PayLifeCost -> CostType.PAY_LIFE
                is DiscardSelfCost ->
                    if (initial.banish) CostType.SELF_BANISH else CostType.SELF_TO_GRAVE

                is MillCost -> CostType.MILL
                is RevealCost -> CostType.REVEAL
                else -> CostType.MOVE
            }
        )
    }
    var amount by remember { mutableIntStateOf((initial as? PayLifeCost)?.amount ?: 500) }
    var count by remember { mutableIntStateOf((initial as? MillCost)?.count ?: 1) }
    var scope by remember {
        mutableStateOf(
            (initial as? MoveCost)?.scope
                ?: CardScope(who = PlayerRef.SELF, zone = ZoneType.HAND, count = 1)
        )
    }
    var destination by remember {
        mutableStateOf((initial as? MoveCost)?.destination ?: MoveDestination.GRAVEYARD)
    }
    var revealScope by remember {
        mutableStateOf((initial as? RevealCost)?.scope ?: RevealCost().scope)
    }
    var revealDuration by remember {
        mutableStateOf((initial as? RevealCost)?.duration ?: RevealDuration.MOMENT)
    }

    fun build(): Cost = when (type) {
        CostType.MOVE -> MoveCost(scope, destination)
        CostType.PAY_LIFE -> PayLifeCost(amount.coerceAtLeast(0))
        CostType.SELF_TO_GRAVE -> DiscardSelfCost(banish = false)
        CostType.SELF_BANISH -> DiscardSelfCost(banish = true)
        CostType.MILL -> MillCost(count.coerceAtLeast(1))
        CostType.REVEAL -> RevealCost(revealScope, revealDuration)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "【コスト】を追加" else "【コスト】を編集") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown("コストの種類", CostType.entries.toList(), type, { it.label }) { type = it }

                when (type) {
                    CostType.MOVE -> {
                        HorizontalDivider()
                        Text(
                            "効果と同じ対象指定が使えます。" +
                                "「自分の墓地のカード1枚をデッキの一番上に戻す」のような" +
                                "コストも書けます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CardScopeEditor(scope, master) { scope = it }
                        Dropdown(
                            "動かす先", MoveDestination.all, destination, { it.label }
                        ) { destination = it }
                    }

                    CostType.PAY_LIFE -> NumberField("支払うライフ", amount) { amount = it }

                    CostType.REVEAL -> {
                        HorizontalDivider()
                        CardScopeEditor(revealScope, master) { revealScope = it }
                        Dropdown(
                            "見せ方", RevealDuration.all, revealDuration, { it.label }
                        ) { revealDuration = it }
                    }

                    CostType.SELF_TO_GRAVE, CostType.SELF_BANISH -> Text(
                        "発動するこのカード自身をコストにします。" +
                            "手札で発動するモンスターを、発動と同時に墓地へ送りたいときに使います。" +
                            "効果の解決後に送りたい場合は、コストではなく【発動後】を指定してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> NumberField("枚数／体数", count) { count = it }
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
    initial: UsageLimit? = null,
    /** 効果番号より前に書く制限のとき、掛ける効果を選ばせるための効果数。 */
    clauseCount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (UsageLimit) -> Unit
) {
    var scope by remember { mutableStateOf(initial?.scope ?: LimitScope.THIS_CARD) }
    var times by remember { mutableIntStateOf(initial?.times ?: 1) }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var clauseIndices by remember { mutableStateOf(initial?.clauseIndices ?: emptyList()) }
    var applies by remember { mutableStateOf(initial?.applies ?: LimitApplies.TOGETHER) }

    fun build() = UsageLimit(
        scope = scope,
        times = times.coerceAtLeast(1),
        categoryId = if (scope == LimitScope.CATEGORY) categoryId else null,
        clauseIndices = if (clauseCount > 0) clauseIndices else emptyList(),
        applies = if (clauseCount > 0) applies else LimitApplies.TOGETHER
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "【制限】を追加" else "【制限】を編集") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown("数える単位", LimitScope.all, scope, { it.label }) { scope = it }
                NumberField("1ターンに発動できる回数", times) { times = it }

                if (clauseCount > 0) {
                    HorizontalDivider()
                    Text(
                        "掛ける効果（選ばなければ全ての効果）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRowSimple {
                        (0 until clauseCount).forEach { index ->
                            Chip(
                                EffectNumbers.circled(index),
                                selected = index in clauseIndices
                            ) {
                                clauseIndices =
                                    if (index in clauseIndices) clauseIndices - index
                                    else clauseIndices + index
                            }
                        }
                    }
                    Dropdown("数え方", LimitApplies.all, applies, { it.label }) { applies = it }
                    Text(
                        "「効果ごとに別々に数える」を選ぶと、①②それぞれが1ターンに1度ずつ" +
                            "発動できるようになります。「まとめて数える」なら、選んだ効果の" +
                            "うちどれか1つしか発動できません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                        EffectTextRenderer.limitToText(
                            build(), master, cardWide = clauseCount > 0
                        ) + "。",
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
