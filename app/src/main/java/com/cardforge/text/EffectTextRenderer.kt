package com.cardforge.text

import com.cardforge.model.*

/**
 * 構造化された効果データを、遊戯王ライクな日本語のカードテキストに整形する。
 *
 * 効果番号（①②③）より前に置かれた【場所】【条件】【コスト】はカードの全ての
 * 効果に掛かり、効果番号の直後に置かれたものはその番号の効果にのみ掛かる、
 * という仕様をそのまま表示に反映する。
 */
object EffectTextRenderer {

    private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")

    fun circledNumber(index: Int): String = CIRCLED.getOrElse(index) { "(${index + 1})" }

    // -----------------------------------------------------------------------
    // 対象指定（主語・目的語・修飾語）
    // -----------------------------------------------------------------------

    private fun zonePrefix(who: PlayerRef, zone: ZoneType): String {
        val needsParticle =
            zone.isHidden || zone == ZoneType.GRAVEYARD || zone == ZoneType.BANISHED ||
                who == PlayerRef.BOTH
        return if (needsParticle) "${who.label}の${zone.label}の" else "${who.label}${zone.label}の"
    }

    /** 「体」か「枚」か。モンスターを指しているなら「体」。 */
    private fun counter(scope: CardScope): String {
        val kind = scope.filters.filterIsInstance<KindFilter>().firstOrNull()?.kind
        return when {
            kind == CardKind.MONSTER -> "体"
            kind != null -> "枚"
            scope.zone == ZoneType.MONSTER_ZONE -> "体"
            else -> "枚"
        }
    }

    /** フィルタ列を「『アララギ』レベル4以下の闇属性ドラゴン族モンスター」のような名詞句にする。 */
    fun filtersToNoun(
        filters: List<CardFilter>,
        master: MasterData,
        fallbackZone: ZoneType? = null
    ): String {
        val sb = StringBuilder()

        filters.filterIsInstance<NameFilter>().forEach { sb.append("「${it.text}」という名前の") }
        filters.filterIsInstance<CategoryFilter>().forEach {
            sb.append("「${master.categoryName(it.categoryId)}」")
        }
        filters.filterIsInstance<LevelFilter>().forEach {
            sb.append("レベル${it.value}${it.cmp.label}の")
        }
        filters.filterIsInstance<AtkFilter>().forEach {
            sb.append("攻撃力${it.value}${it.cmp.label}の")
        }
        filters.filterIsInstance<DefFilter>().forEach {
            sb.append("守備力${it.value}${it.cmp.label}の")
        }
        filters.filterIsInstance<PositionFilter>().forEach { sb.append("${it.position.label}の") }
        filters.filterIsInstance<AttributeFilter>().forEach {
            sb.append("${master.attributeName(it.attributeId)}属性")
        }
        filters.filterIsInstance<RaceFilter>().forEach { sb.append(master.raceName(it.raceId)) }

        val kind = filters.filterIsInstance<KindFilter>().firstOrNull()?.kind
        sb.append(
            when {
                kind == CardKind.MONSTER -> "モンスター"
                kind == CardKind.SPELL -> "魔法カード"
                kind == CardKind.TRAP -> "罠カード"
                fallbackZone == ZoneType.MONSTER_ZONE -> "モンスター"
                else -> "カード"
            }
        )
        return sb.toString()
    }

    /** 「相手フィールドの闇属性モンスター1体」のような、数まで含めた対象表現。 */
    fun scopeToText(scope: CardScope, master: MasterData, withCount: Boolean = true): String {
        if (scope.selfOnly) return "このカード"
        val prefix = zonePrefix(scope.who, scope.zone)
        val noun = filtersToNoun(scope.filters, master, scope.zone)
        return when {
            scope.selection == SelectionMode.ALL -> "${prefix}全ての$noun"
            withCount -> "$prefix$noun${scope.count}${counter(scope)}"
            else -> "$prefix$noun"
        }
    }

    /** 「を選んで」「をランダムに」など、対象と述語をつなぐ部分。 */
    private fun selectionParticle(scope: CardScope): String {
        // 「このカード」を指しているときは選ぶ余地が無い。
        if (scope.selfOnly) return "を"
        return selectionParticleFor(scope)
    }

    private fun selectionParticleFor(scope: CardScope): String = when (scope.selection) {
        SelectionMode.CHOOSE -> "を選んで"
        SelectionMode.RANDOM -> "をランダムに"
        SelectionMode.ALL -> "を"
    }

    // -----------------------------------------------------------------------
    // 述語（効果）
    // -----------------------------------------------------------------------

    fun actionToText(action: Action, master: MasterData): String = when (action) {
        is DestroyAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "破壊する"

        is BanishAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "除外する"

        is ToHandAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "手札に加える"

        is ToGraveAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "墓地へ送る"

        is ToDeckAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "デッキの${if (action.toBottom) "一番下" else "一番上"}に戻す"

        is SpecialSummonAction -> {
            val positions = action.choices.joinToString("または") { it.label }
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "${action.controller.label}のモンスターゾーンに${positions}で特殊召喚する"
        }

        is ModifyStatAction -> {
            val spec = action.amountSpec
            val negative = spec is FixedValue && spec.value < 0
            val verb = if (negative) "ダウンする" else "アップする"
            val amount =
                if (spec is FixedValue) kotlin.math.abs(spec.value).toString()
                else valueToText(spec, master)
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "その${action.stat.label}を${amount}$verb"
        }

        is ChangePositionAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "${action.position.label}に変更する"

        is DrawAction -> "${action.who.label}はカードを${action.count}枚ドローする"

        is DamageAction ->
            "${action.who.label}に${valueToText(action.amountSpec, master)}ポイントのダメージを与える"

        is RecoverAction ->
            "${action.who.label}のライフを${valueToText(action.amountSpec, master)}ポイント回復する"

        is DiscardAction ->
            "${action.who.label}は手札を${if (action.random) "ランダムに" else ""}${action.count}枚捨てる"

        is MillAction ->
            "${action.who.label}のデッキの上からカードを${action.count}枚墓地へ送る"

        is SetSpellTrapAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "魔法・罠ゾーンにセットする"

        is PlaceSpellTrapAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "魔法・罠ゾーンに表側で置く"

        is ActivateCardAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "発動する"

        is GrantProtectionAction ->
            scopeToText(action.scope, master, withCount = false) + "は" + action.kind.label

        is PreventAttackAction ->
            scopeToText(action.scope, master, withCount = false) + "は攻撃できない"

        NegateAction -> "相手の効果の発動を無効にし、そのカードを破壊する"
    }

    // -----------------------------------------------------------------------
    // 条件・コスト
    // -----------------------------------------------------------------------

    /** 「相手の闇属性モンスターが破壊された場合」のようなイベント条件の文。 */
    fun eventConditionToText(condition: EventCondition, master: MasterData): String {
        if (condition.selfOnly) return "このカードが${condition.event.label}場合"
        if (condition.event.isPlayerEvent) {
            return "${condition.who.label}が${condition.event.label}場合"
        }
        val noun = filtersToNoun(condition.filters, master, ZoneType.FIELD)
        return "${condition.who.label}の${noun}が${condition.event.label}場合"
    }

    fun conditionToText(condition: Condition, master: MasterData): String = when (condition) {
        is EventCondition -> eventConditionToText(condition, master)

        is CardExistsCondition -> {
            val noun = scopeToText(condition.scope, master, withCount = false)
            val counterWord = counter(condition.scope)
            if (condition.negate) {
                "${noun}が存在しない"
            } else {
                "${noun}が${condition.atLeast}${counterWord}以上存在する"
            }
        }

        is SelfZoneCondition ->
            if (condition.zones.isEmpty()) "このカードがどこかに存在する場合"
            else "このカードが" + condition.zones.joinToString("または") { it.label } + "に存在する場合"

        is PhaseCondition ->
            if (condition.phases.isEmpty()) "いつでも"
            else condition.phases.joinToString("または") { it.label } + "である"

        is LifeCondition ->
            "${condition.who.label}のライフが${condition.value}${condition.cmp.label}である"

        is ZoneCountCondition ->
            "${condition.who.label}の${condition.zone.label}のカードが" +
                "${condition.value}枚${condition.cmp.label}である"
    }

    fun costToText(cost: Cost, master: MasterData): String = when (cost) {
        is PayLifeCost -> "ライフを${cost.amount}ポイント払う"

        is DiscardCost ->
            if (cost.filters.isEmpty()) "手札を${cost.count}枚捨てる"
            else "手札から${filtersToNoun(cost.filters, master)}を${cost.count}枚捨てる"

        is TributeCost ->
            if (cost.filters.isEmpty()) "自分フィールドのモンスター${cost.count}体をリリースする"
            else "自分フィールドの${filtersToNoun(cost.filters, master, ZoneType.MONSTER_ZONE)}" +
                "${cost.count}体をリリースする"

        is BanishFromGraveCost ->
            if (cost.filters.isEmpty()) "自分の墓地のカード${cost.count}枚を除外する"
            else "自分の墓地の${filtersToNoun(cost.filters, master)}${cost.count}枚を除外する"

        is MillCost -> "自分のデッキの上からカードを${cost.count}枚墓地へ送る"

        is DiscardSelfCost ->
            if (cost.banish) "このカードを除外する" else "このカードを墓地へ送る"

        is MoveCost ->
            scopeToText(cost.scope, master) + "を" + cost.destination.label
    }

    /** 数値の指定を文にする。「自分の墓地のモンスターの数×100」など。 */
    fun valueToText(spec: ValueSpec, master: MasterData): String = when (spec) {
        is FixedValue -> spec.value.toString()
        is CountValue -> buildString {
            // 数を数えるだけなので「全ての」は付けずに読ませる。
            val scope = spec.scope.copy(selection = SelectionMode.CHOOSE)
            append(scopeToText(scope, master, withCount = false))
            append("の数×")
            append(spec.multiplier)
            if (spec.base != 0) append("＋${spec.base}")
        }
    }

    /**
     * 【制限】の一文。
     *
     * [cardWide] は効果番号より前に書かれた制限かどうか。
     * 掛ける効果を指定している場合は、その番号も文に出す。
     */
    fun limitToText(limit: UsageLimit, master: MasterData, cardWide: Boolean): String {
        val times = limit.times.coerceAtLeast(1)
        val numbers = limit.clauseIndices.sorted().joinToString("") { circledNumber(it) }

        // 数える単位だけで意味が通る場合は、主語を書かずに簡潔にする。
        val head = when {
            !cardWide -> "この効果は"
            limit.clauseIndices.isNotEmpty() && limit.applies == LimitApplies.EACH ->
                "${numbers}はそれぞれ"

            limit.clauseIndices.isNotEmpty() -> "${numbers}は合わせて"
            limit.applies == LimitApplies.EACH -> "それぞれの効果は"
            limit.scope == LimitScope.THIS_CARD -> "このカードは"
            else -> ""
        }

        val counting = when (limit.scope) {
            LimitScope.THIS_CARD -> ""
            LimitScope.SAME_NAME -> "同名カードを含めて"
            LimitScope.CATEGORY ->
                if (limit.categoryId != null) "「${master.categoryName(limit.categoryId)}」カードを含めて"
                else "このカードと同じカテゴリのカードを含めて"
        }

        return if (times == 1) "$head${counting}1ターンに1度しか発動できない"
        else "$head${counting}1ターンに${times}度まで発動できる"
    }

    // -----------------------------------------------------------------------
    // カード全体
    // -----------------------------------------------------------------------

    /**
     * カードの効果テキスト全体を組み立てる。効果を持たない場合は空文字。
     * 手直ししたテキストがあればそれをそのまま返す。
     */
    fun render(card: CardDef, master: MasterData): String {
        card.textOverride?.takeIf { it.isNotBlank() }?.let { return it }
        return renderGenerated(card, master)
    }

    /** 手直しを反映しない、データから組み立てただけのテキスト。 */
    fun renderGenerated(card: CardDef, master: MasterData): String {
        val lines = mutableListOf<String>()
        val effect = card.effect ?: return ""
        if (effect.isEmpty) return ""

        // 効果番号より前の共通指定。
        if (effect.locations.isNotEmpty()) {
            lines += "【場所】" + effect.locations.joinToString("、") { it.label }
        } else if (card.kind != CardKind.MONSTER) {
            // 魔法・罠で記述が省略されている場合は「フィールドで発動」。
            lines += "【場所】" + ActivationLocation.FIELD.label
        }
        if (effect.conditions.isNotEmpty()) {
            lines += "【条件】" + orderedConditions(effect.conditions).joinToString("、かつ") {
                conditionToText(it, master)
            }
        }
        if (effect.costs.isNotEmpty()) {
            lines += "【コスト】" + effect.costs.joinToString("、") { costToText(it, master) }
        }
        if (effect.limits.isNotEmpty()) {
            lines += "【制限】" + effect.limits.joinToString("、") {
                limitToText(it, master, cardWide = true)
            }
        }
        // 【発動後】は、省略時の既定と違うときだけ明記する。
        // （魔法・罠は「墓地へ送る」、モンスターは「そのまま残す」が既定）
        effect.afterActivation
            ?.takeIf { it != EffectText.defaultAfterActivation(card.kind) }
            ?.let { lines += "【発動後】" + it.label }

        // 各効果。
        effect.clauses.forEachIndexed { index, clause ->
            if (!clause.hasWork) return@forEachIndexed
            val sb = StringBuilder(circledNumber(index) + "：")

            if (clause.locations.isNotEmpty()) {
                sb.append("【場所】" + clause.locations.joinToString("、") { it.label } + " ")
            }
            val clauseConditions = orderedConditions(effect.conditionsFor(index) - effect.conditions)
            if (clauseConditions.isNotEmpty()) {
                sb.append(
                    "【条件】" + clauseConditions.joinToString("、かつ") {
                        conditionToText(it, master)
                    } + " "
                )
            }
            if (clause.costs.isNotEmpty()) {
                sb.append(
                    "【コスト】" + clause.costs.joinToString("、") { costToText(it, master) } + " "
                )
            }
            // 効果番号ごとの【発動後】は、明示されていれば常に書く。
            clause.afterActivation?.let { sb.append("【発動後】" + it.label + " ") }
            if (effect.isOnActivation(index)) {
                sb.append("このカードの発動時に、")
                sb.append(
                    clause.actions.joinToString("。その後、") { actionToText(it, master) }
                )
            } else if (effect.isContinuous(index)) {
                // 「このカードがフィールドに存在する限り、〜」という書き出しにする。
                val where = effectiveLocationLabel(effect, index)
                sb.append("このカードが${where}に存在する限り、")
                sb.append(
                    clause.actions.joinToString("。また、") {
                        continuousActionToText(it, master)
                    }
                )
            } else if (clause.actions.isNotEmpty()) {
                sb.append(
                    clause.actions.mapIndexed { position, action ->
                        val text = actionToText(action, master)
                        // 最後の文だけ「〜できる（任意）／〜する（強制）」を書き分ける。
                        if (position == clause.actions.lastIndex && effect.isTriggered(index)) {
                            applyMode(text, clause.mode)
                        } else {
                            text
                        }
                    }.joinToString("。その後、")
                )
            }
            if (clause.actions.isNotEmpty()) sb.append("。")
            appendBranches(sb, clause, master)
            effect.clauseLimitsFor(index).forEach { limit ->
                sb.append(limitToText(limit, master, cardWide = false) + "。")
            }
            lines += sb.toString()
        }

        return lines.joinToString("\n")
    }

    /** 永続の効果が有効になる場所。省略時はフィールド。 */
    private fun effectiveLocationLabel(effect: EffectText, index: Int): String {
        val locations = effect.locationsFor(index)
        return if (locations.isEmpty()) ActivationLocation.FIELD.label
        else locations.joinToString("・") { it.label }
    }

    /**
     * 永続の効果は「〜する」ではなく状態を述べる文にする。
     * 「このカード自身」が対象のときは、書き出しと重ならないよう主語を省く。
     */
    fun continuousActionToText(action: Action, master: MasterData): String {
        fun subject(scope: CardScope): String =
            if (scope.selfOnly) "" else scopeToText(scope, master, withCount = false)

        return when (action) {
            is ModifyStatAction -> {
                val spec = action.amountSpec
                val negative = spec is FixedValue && spec.value < 0
                val verb = if (negative) "ダウンする" else "アップする"
                val amount =
                    if (spec is FixedValue) kotlin.math.abs(spec.value).toString()
                    else valueToText(spec, master)
                val who = subject(action.scope)
                val head = if (who.isEmpty()) "その" else "${who}の"
                "$head${action.stat.label}は${amount}$verb"
            }

            is SetSpellTrapAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "魔法・罠ゾーンにセットする"

        is PlaceSpellTrapAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "魔法・罠ゾーンに表側で置く"

        is ActivateCardAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "発動する"

        is GrantProtectionAction -> {
                val who = subject(action.scope)
                if (who.isEmpty()) action.kind.label else "${who}は${action.kind.label}"
            }

            is PreventAttackAction -> {
                val who = subject(action.scope)
                if (who.isEmpty()) "攻撃できない" else "${who}は攻撃できない"
            }

            else -> actionToText(action, master)
        }
    }

    /** 「●条件：効果」の形で場合分けを書き出す。 */
    private fun appendBranches(
        sb: StringBuilder,
        clause: EffectClause,
        master: MasterData
    ) {
        val branches = clause.branches.filter { it.actions.isNotEmpty() }
        if (branches.isEmpty()) return

        sb.append(clause.branchMode.lead).append("。")
        branches.forEach { branch ->
            sb.append("\n●")
            if (branch.conditions.isNotEmpty()) {
                sb.append(
                    orderedConditions(branch.conditions).joinToString("、かつ") {
                        conditionToText(it, master)
                    }
                )
            } else {
                sb.append("それ以外の場合")
            }
            sb.append("：")
            sb.append(branch.actions.joinToString("。その後、") { actionToText(it, master) })
            sb.append("。")
        }
    }

    /** イベント条件を先に読ませる。 */
    private fun orderedConditions(conditions: List<Condition>): List<Condition> =
        conditions.filterIsInstance<EventCondition>() +
            conditions.filterNot { it is EventCondition }

    /** 述語の語尾を、任意発動なら「できる」に置き換える。 */
    private fun applyMode(text: String, mode: ActivationMode): String {
        if (mode == ActivationMode.MANDATORY) return text
        return when {
            text.endsWith("する") -> text.removeSuffix("する") + "できる"
            text.endsWith("送る") -> text.removeSuffix("送る") + "送ることができる"
            text.endsWith("加える") -> text.removeSuffix("加える") + "加えることができる"
            text.endsWith("戻す") -> text.removeSuffix("戻す") + "戻すことができる"
            text.endsWith("与える") -> text.removeSuffix("与える") + "与えることができる"
            text.endsWith("捨てる") -> text.removeSuffix("捨てる") + "捨てさせることができる"
            else -> "$text ことができる".replace(" ", "")
        }
    }

    /** カード一覧などで使う1行の要約。 */
    fun summary(card: CardDef, master: MasterData): String = when (card.kind) {
        CardKind.MONSTER -> buildString {
            append("★${card.level} ")
            append("${master.attributeName(card.attributeId)}/")
            append("${master.raceName(card.raceId)} ")
            append("ATK ${card.atk} / DEF ${card.def}")
        }

        CardKind.SPELL -> "魔法カード"
        CardKind.TRAP -> "罠カード"
    }
}
