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
    GRANT_EFFECT("効果を与える（永続向き）", true),
    ADVANCE_PHASE("フェイズ・ターンを進める", false),
    MATERIAL_SUMMON("素材を送って特殊召喚する（儀式・融合など）", false),
    CREATE_TOKEN("トークンを特殊召喚する", false),
    ADD_COUNTER("カウンターを乗せる", true),
    REMOVE_COUNTER("カウンターを取り除く", true),
    REPLACE_DESTINATION("墓地へ送られる代わりに（永続向き）", true),
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
    is GrantEffectAction -> ActionType.GRANT_EFFECT
    is AdvancePhaseAction -> ActionType.ADVANCE_PHASE
    is MaterialSummonAction -> ActionType.MATERIAL_SUMMON
    is CreateTokenAction -> ActionType.CREATE_TOKEN
    is AddCounterAction -> ActionType.ADD_COUNTER
    is RemoveCounterAction -> ActionType.REMOVE_COUNTER
    is ReplaceDestinationAction -> ActionType.REPLACE_DESTINATION
    // 旧データ用。編集画面では【制限】として扱う。
    is RestrictSummonAction -> ActionType.NEGATE
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
    is GrantEffectAction -> action.scope
    is RevealAction -> action.scope
    is AddCounterAction -> action.scope
    is RemoveCounterAction -> action.scope
    is ReplaceDestinationAction -> action.scope
    else -> null
}

/**
 * 「主語・目的語・修飾語・述語」を選んで1つの効果を組み立てるダイアログ。
 */
@Composable
fun ActionDialog(
    initial: Action?,
    master: MasterData,
    /** 「トークンを特殊召喚する」で選べるトークンのカード。 */
    tokenCards: List<CardDef> = emptyList(),
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
    var revealDuration by remember {
        mutableStateOf(
            (initial as? RevealAction)?.duration ?: RevealDuration.MOMENT
        )
    }
    var granted by remember {
        mutableStateOf((initial as? GrantEffectAction)?.granted ?: EffectClause())
    }
    var advance by remember {
        mutableStateOf((initial as? AdvancePhaseAction)?.kind ?: PhaseAdvance.SKIP_PHASE)
    }
    var material by remember {
        mutableStateOf((initial as? MaterialSummonAction) ?: MaterialSummonAction())
    }
    var counterId by remember {
        mutableStateOf(
            when (initial) {
                is AddCounterAction -> initial.counterId
                is RemoveCounterAction -> initial.counterId
                else -> null
            } ?: master.counters.firstOrNull()?.id
        )
    }
    var counterAmount by remember {
        mutableIntStateOf(
            when (initial) {
                is AddCounterAction -> initial.amount
                is RemoveCounterAction -> initial.amount
                else -> 1
            }
        )
    }
    var tokenId by remember { mutableStateOf((initial as? CreateTokenAction)?.tokenCardId) }
    var tokenCount by remember {
        mutableStateOf((initial as? CreateTokenAction)?.countSpec ?: FixedValue(1))
    }
    var tokenPositions by remember {
        mutableStateOf((initial as? CreateTokenAction)?.choices ?: listOf(Position.ATTACK))
    }
    var replaceTo by remember {
        mutableStateOf((initial as? ReplaceDestinationAction)?.to ?: MoveDestination.BANISHED)
    }
    var protectionFrom by remember {
        mutableStateOf((initial as? GrantProtectionAction)?.from ?: PlayerRef.OPPONENT)
    }
    var showGrantedAction by remember { mutableStateOf<Int?>(null) }
    var addingGrantedAction by remember { mutableStateOf(false) }
    var countValue by remember {
        mutableStateOf(
            when (initial) {
                is DrawAction -> initial.countSpec
                is MillAction -> initial.countSpec
                is DiscardAction -> initial.countSpec
                else -> FixedValue(1)
            }
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
        ActionType.DRAW -> DrawAction(who, countValue = countValue)
        ActionType.DAMAGE -> DamageAction(who, amountValue = amountValue)
        ActionType.RECOVER -> RecoverAction(who, amountValue = amountValue)
        ActionType.DISCARD -> DiscardAction(who, random = randomDiscard, countValue = countValue)
        ActionType.MILL -> MillAction(who, countValue = countValue)
        ActionType.SET_SPELL_TRAP -> SetSpellTrapAction(scope)
        ActionType.PLACE_SPELL_TRAP -> PlaceSpellTrapAction(scope)
        ActionType.ACTIVATE_CARD -> ActivateCardAction(scope)
        ActionType.GRANT_PROTECTION -> GrantProtectionAction(scope, protection, protectionFrom)
        ActionType.PREVENT_ATTACK -> PreventAttackAction(scope)
        ActionType.GRANT_EFFECT -> GrantEffectAction(scope, granted)
        ActionType.ADVANCE_PHASE -> AdvancePhaseAction(advance)
        ActionType.MATERIAL_SUMMON -> material
        ActionType.CREATE_TOKEN ->
            CreateTokenAction(tokenId, 1, who, tokenPositions, countValue = tokenCount)
        ActionType.ADD_COUNTER -> AddCounterAction(scope, counterId, counterAmount)
        ActionType.REMOVE_COUNTER -> RemoveCounterAction(scope, counterId, counterAmount)
        ActionType.REPLACE_DESTINATION -> ReplaceDestinationAction(scope, replaceTo)
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
                        ValueSpecEditor("枚数", countValue, master) { countValue = it }
                    }

                    ActionType.DAMAGE, ActionType.RECOVER -> {
                        Dropdown("対象プレイヤー", PlayerRef.all, who, { it.label }) { who = it }
                        ValueSpecEditor("ポイント", amountValue, master) { amountValue = it }
                    }

                    ActionType.DISCARD -> {
                        Dropdown("対象プレイヤー", PlayerRef.all, who, { it.label }) { who = it }
                        ValueSpecEditor("枚数", countValue, master) { countValue = it }
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
                        if (protection.usesSide) {
                            Dropdown(
                                "誰の効果に対する耐性か", PlayerRef.all, protectionFrom, { it.label }
                            ) { protectionFrom = it }
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

                    ActionType.GRANT_EFFECT -> {
                        HorizontalDivider()
                        Text(
                            "【発動タイプ】を「永続」にした効果に書いてください。" +
                                "このカードが【場所】にある間、上で指定したカードが" +
                                "下の効果を持つようになります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Dropdown(
                            "与える効果の発動タイプ",
                            ActivationMode.all.filter { it != ActivationMode.ON_ACTIVATION },
                            granted.mode,
                            { it.label }
                        ) { granted = granted.copy(mode = it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = granted.quick,
                                onCheckedChange = { granted = granted.copy(quick = it) }
                            )
                            Text("与える効果を誘発即時にする")
                        }

                        Text(
                            "与える効果の中身",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        granted.actions.forEachIndexed { position, given ->
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
                                        EffectTextRenderer.actionToText(given, master) + "。",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 10.dp)
                                    )
                                    TextButton(onClick = { showGrantedAction = position }) {
                                        Text("編集")
                                    }
                                    TextButton(onClick = {
                                        granted = granted.copy(
                                            actions = granted.actions.toMutableList()
                                                .also { it.removeAt(position) }
                                        )
                                    }) { Text("削除") }
                                }
                            }
                        }
                        Chip("＋ 与える効果の文を追加") { addingGrantedAction = true }
                    }

                    ActionType.MATERIAL_SUMMON -> {
                        HorizontalDivider()
                        Text(
                            "遊戯王の儀式召喚や融合召喚のように、素材を送って特殊召喚します。" +
                                "素材の条件を満たせないと発動できません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            "特殊召喚するモンスター（どこから・どんなカード）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CardScopeEditor(material.summon, master) {
                            material = material.copy(summon = it)
                        }

                        HorizontalDivider()
                        Text(
                            "素材にするカード（どこから・どんなカード）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CardScopeEditor(material.material, master, showCount = false) {
                            material = material.copy(material = it)
                        }
                        Dropdown(
                            "素材の行き先",
                            MoveDestination.all,
                            material.destination,
                            { it.label }
                        ) { material = material.copy(destination = it) }
                        Dropdown(
                            "素材に求める条件",
                            MaterialRequirement.all,
                            material.requirement,
                            { it.label }
                        ) { material = material.copy(requirement = it) }
                        if (material.requirement == MaterialRequirement.COUNT) {
                            NumberField("必要な枚数", material.count) {
                                material = material.copy(count = it.coerceIn(1, 9))
                            }
                        }

                        Text(
                            "選べる表示形式",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            Position.all.forEach { candidate ->
                                Chip(candidate.label, selected = candidate in material.choices) {
                                    val current = material.choices
                                    material = material.copy(
                                        positionChoices =
                                            if (candidate in current) {
                                                (current - candidate).ifEmpty { listOf(candidate) }
                                            } else {
                                                current + candidate
                                            }
                                    )
                                }
                            }
                        }
                    }

                    ActionType.ADD_COUNTER, ActionType.REMOVE_COUNTER -> {
                        HorizontalDivider()
                        CounterPicker(master, counterId) { counterId = it }
                        NumberField("個数", counterAmount) {
                            counterAmount = it.coerceIn(1, 20)
                        }
                    }

                    ActionType.CREATE_TOKEN -> {
                        HorizontalDivider()
                        Dropdown("出す側", PlayerRef.all, who, { it.label }) { who = it }
                        if (tokenCards.isEmpty()) {
                            Text(
                                "トークンのカードがまだありません。" +
                                    "カード作成画面で「トークン」を付けたカードを作ってください。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Dropdown(
                                "出すトークン",
                                tokenCards,
                                tokenCards.firstOrNull { it.id == tokenId },
                                { it.name }
                            ) { tokenId = it.id }
                        }
                        ValueSpecEditor("体数", tokenCount, master) { tokenCount = it }
                        Text(
                            "選べる表示形式",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            Position.all.forEach { candidate ->
                                Chip(candidate.label, selected = candidate in tokenPositions) {
                                    tokenPositions =
                                        if (candidate in tokenPositions) {
                                            (tokenPositions - candidate)
                                                .ifEmpty { listOf(candidate) }
                                        } else {
                                            tokenPositions + candidate
                                        }
                                }
                            }
                        }
                    }

                    ActionType.REPLACE_DESTINATION -> {
                        HorizontalDivider()
                        Dropdown(
                            "墓地へ送られる代わりに", MoveDestination.all, replaceTo, { it.label }
                        ) { replaceTo = it }
                        Text(
                            "【発動タイプ】を「永続」にした効果に書いてください。" +
                                "上で指定したカードが墓地へ送られるとき、行き先が差し替わります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ActionType.ADVANCE_PHASE -> {
                        HorizontalDivider()
                        Dropdown("どう進めるか", PhaseAdvance.all, advance, { it.label }) {
                            advance = it
                        }
                        Text(
                            "効果の処理が全て終わってから適用されます。" +
                                "相手ターンに使いたい場合は【誘発即時】を付けてください。",
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

    // 与える効果の中身は、同じダイアログをもう一段開いて組み立てる。
    if (addingGrantedAction || showGrantedAction != null) {
        val position = showGrantedAction
        ActionDialog(
            initial = position?.let { granted.actions.getOrNull(it) },
            master = master,
            tokenCards = tokenCards,
            onDismiss = {
                addingGrantedAction = false
                showGrantedAction = null
            },
            onConfirm = { given ->
                granted = granted.copy(
                    actions = if (position == null) granted.actions + given
                    else granted.actions.toMutableList().also { it[position] = given }
                )
                addingGrantedAction = false
                showGrantedAction = null
            }
        )
    }
}

// ===========================================================================
// 条件
// ===========================================================================

private enum class ConditionType(val label: String) {
    EVENT("〜した場合／〜したターン（出来事）"),
    SELF_ZONE("このカードが〇〇にある"),
    PHASE("〇〇フェイズである"),
    EXISTS("特定のカードが存在する"),
    LIFE("ライフが一定値である"),
    ZONE_COUNT("領域の枚数が一定値である"),
    COUNTER("カウンターが乗っている"),
    ANY_OF("いずれかを満たす（または）")
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
                is AnyOfCondition -> ConditionType.ANY_OF
                is CounterCondition -> ConditionType.COUNTER
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
    var eventWindow by remember {
        mutableStateOf((initial as? EventCondition)?.window ?: EventWindow.IMMEDIATE)
    }
    var eventSourceFilters by remember {
        mutableStateOf((initial as? EventCondition)?.sourceFilters ?: emptyList())
    }
    var showSourceFilter by remember { mutableStateOf(false) }
    var anyOf by remember {
        mutableStateOf((initial as? AnyOfCondition)?.conditions ?: emptyList())
    }
    var counterScope by remember {
        mutableStateOf((initial as? CounterCondition)?.scope ?: CardScope(selfOnly = true))
    }
    var counterId by remember {
        mutableStateOf(
            (initial as? CounterCondition)?.counterId ?: master.counters.firstOrNull()?.id
        )
    }
    var counterValue by remember { mutableIntStateOf((initial as? CounterCondition)?.value ?: 1) }
    var counterCmp by remember { mutableStateOf((initial as? CounterCondition)?.cmp ?: Cmp.GE) }
    var editingAnyOf by remember { mutableStateOf<Int?>(null) }
    var addingAnyOf by remember { mutableStateOf(false) }
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
            cause = eventCause,
            window = eventWindow,
            sourceFilters = eventSourceFilters
        )

        ConditionType.ANY_OF -> AnyOfCondition(anyOf)
        ConditionType.COUNTER ->
            CounterCondition(counterScope, counterId, counterCmp, counterValue)

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
                            "いつまで見るか", EventWindow.all, eventWindow, { it.label }
                        ) { eventWindow = it }
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

                        Text(
                            "出来事を起こしたカードを限定する（任意）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "「「VALIS」モンスターの効果によって」のように、" +
                                "出来事を起こした側のカードを絞れます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            eventSourceFilters.forEachIndexed { index, filter ->
                                Chip(filterChipLabel(filter, master) + " ✕", selected = true) {
                                    eventSourceFilters = eventSourceFilters.toMutableList()
                                        .also { it.removeAt(index) }
                                }
                            }
                            Chip("＋ 起こした側の条件") { showSourceFilter = true }
                        }

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

                    ConditionType.COUNTER -> {
                        HorizontalDivider()
                        CardScopeEditor(counterScope, master, showCount = false) {
                            counterScope = it
                        }
                        CounterPicker(master, counterId) { counterId = it }
                        NumberField("個数", counterValue) { counterValue = it.coerceAtLeast(0) }
                        Dropdown("比較", Cmp.all, counterCmp, { it.label }) { counterCmp = it }
                    }

                    ConditionType.ANY_OF -> {
                        HorizontalDivider()
                        Text(
                            "並べた条件のうち、どれか1つを満たせば発動できます。" +
                                "「かつ」で結びたい条件は、条件を分けて追加してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        anyOf.forEachIndexed { position, inner ->
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
                                        EffectTextRenderer.conditionToText(inner, master),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 10.dp)
                                    )
                                    TextButton(onClick = { editingAnyOf = position }) {
                                        Text("編集")
                                    }
                                    TextButton(onClick = {
                                        anyOf = anyOf.toMutableList().also { it.removeAt(position) }
                                    }) { Text("削除") }
                                }
                            }
                        }
                        Chip("＋ 「または」でつなぐ条件を追加") { addingAnyOf = true }
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

    if (showSourceFilter) {
        FilterDialog(
            master = master,
            onDismiss = { showSourceFilter = false },
            onConfirm = {
                eventSourceFilters = eventSourceFilters + it
                showSourceFilter = false
            }
        )
    }

    // 「または」でつなぐ条件は、同じダイアログをもう一段開いて作る。
    if (addingAnyOf || editingAnyOf != null) {
        val position = editingAnyOf
        ConditionDialog(
            master = master,
            initial = position?.let { anyOf.getOrNull(it) },
            onDismiss = {
                addingAnyOf = false
                editingAnyOf = null
            },
            onConfirm = { inner ->
                anyOf = if (position == null) anyOf + inner
                else anyOf.toMutableList().also { it[position] = inner }
                addingAnyOf = false
                editingAnyOf = null
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
    REVEAL("カードを相手に見せる（公開する）"),
    COUNTER("カウンターを取り除く")
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
                is CounterCost -> CostType.COUNTER
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
    var counterScope by remember {
        mutableStateOf((initial as? CounterCost)?.scope ?: CardScope(selfOnly = true))
    }
    var counterId by remember {
        mutableStateOf((initial as? CounterCost)?.counterId ?: master.counters.firstOrNull()?.id)
    }
    var counterAmount by remember { mutableIntStateOf((initial as? CounterCost)?.amount ?: 1) }

    fun build(): Cost = when (type) {
        CostType.MOVE -> MoveCost(scope, destination)
        CostType.PAY_LIFE -> PayLifeCost(amount.coerceAtLeast(0))
        CostType.SELF_TO_GRAVE -> DiscardSelfCost(banish = false)
        CostType.SELF_BANISH -> DiscardSelfCost(banish = true)
        CostType.MILL -> MillCost(count.coerceAtLeast(1))
        CostType.REVEAL -> RevealCost(revealScope, revealDuration)
        CostType.COUNTER -> CounterCost(counterScope, counterId, counterAmount)
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

                    CostType.COUNTER -> {
                        HorizontalDivider()
                        CardScopeEditor(counterScope, master, showCount = false) {
                            counterScope = it
                        }
                        CounterPicker(master, counterId) { counterId = it }
                        NumberField("取り除く個数", counterAmount) {
                            counterAmount = it.coerceIn(1, 20)
                        }
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

// ===========================================================================
// 【制限】召喚の縛り
// ===========================================================================

/**
 * 「このカードを発動するターン、〜を特殊召喚できない」という【制限】のダイアログ。
 *
 * 効果ではなく発動そのものに付く制限なので、効果を無効にされても掛かったままになる。
 */
@Composable
fun SummonLockDialog(
    master: MasterData,
    initial: SummonLock? = null,
    onDismiss: () -> Unit,
    onConfirm: (SummonLock) -> Unit
) {
    var who by remember { mutableStateOf(initial?.who ?: PlayerRef.SELF) }
    var summon by remember { mutableStateOf(initial?.summon ?: SummonKind.SPECIAL) }
    var filters by remember { mutableStateOf(initial?.filters ?: emptyList()) }
    var except by remember { mutableStateOf(initial?.except ?: true) }
    var showFilter by remember { mutableStateOf(false) }

    fun build() = SummonLock(who, summon, filters, except)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "召喚の制限を追加" else "召喚の制限を編集") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "発動そのものに付く制限なので、効果を無効にされても掛かったままになります。" +
                        "制限はこのターンの間だけ続きます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Dropdown("制限を受ける側", PlayerRef.all, who, { it.label }) { who = it }
                Dropdown("制限する召喚", SummonKind.all, summon, { it.label }) { summon = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = except, onCheckedChange = { except = it })
                    Text("指定したカード「以外」を出せなくする")
                }
                Text(
                    "対象のカードの条件（空ならモンスター全体）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRowSimple {
                    filters.forEachIndexed { index, filter ->
                        Chip(filterChipLabel(filter, master) + " ✕", selected = true) {
                            filters = filters.toMutableList().also { it.removeAt(index) }
                        }
                    }
                    Chip("＋ 条件を追加") { showFilter = true }
                }

                HorizontalDivider()
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Text(
                        EffectTextRenderer.summonLockToText(build(), master) + "。",
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

    if (showFilter) {
        FilterDialog(
            master = master,
            onDismiss = { showFilter = false },
            onConfirm = {
                filters = filters + it
                showFilter = false
            }
        )
    }
}

/** カウンターの種類を選ぶ。まだ作っていない場合は案内を出す。 */
@Composable
fun CounterPicker(
    master: MasterData,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    if (master.counters.isEmpty()) {
        Text(
            "カウンターの種類がまだありません。" +
                "「属性・種族・カテゴリ」の画面で追加できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    Dropdown(
        "カウンターの種類",
        master.counters,
        master.counters.firstOrNull { it.id == selected },
        { it.name }
    ) { onSelect(it.id) }
}
