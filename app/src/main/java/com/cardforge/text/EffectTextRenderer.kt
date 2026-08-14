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
        val prefix = zonePrefix(scope.who, scope.zone)
        val noun = filtersToNoun(scope.filters, master, scope.zone)
        return when {
            scope.selection == SelectionMode.ALL -> "${prefix}全ての$noun"
            withCount -> "$prefix$noun${scope.count}${counter(scope)}"
            else -> "$prefix$noun"
        }
    }

    /** 「を選んで」「をランダムに」など、対象と述語をつなぐ部分。 */
    private fun selectionParticle(scope: CardScope): String = when (scope.selection) {
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

        is SpecialSummonAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "${action.controller.label}のモンスターゾーンに${action.position.label}で特殊召喚する"

        is ModifyStatAction -> {
            val verb = if (action.delta >= 0) "アップする" else "ダウンする"
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "その${action.stat.label}を${kotlin.math.abs(action.delta)}$verb"
        }

        is ChangePositionAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "${action.position.label}に変更する"

        is DrawAction -> "${action.who.label}はカードを${action.count}枚ドローする"

        is DamageAction -> "${action.who.label}に${action.amount}ポイントのダメージを与える"

        is RecoverAction -> "${action.who.label}のライフを${action.amount}ポイント回復する"

        is DiscardAction ->
            "${action.who.label}は手札を${if (action.random) "ランダムに" else ""}${action.count}枚捨てる"

        is MillAction ->
            "${action.who.label}のデッキの上からカードを${action.count}枚墓地へ送る"

        NegateAction -> "相手の効果の発動を無効にし、そのカードを破壊する"
    }

    // -----------------------------------------------------------------------
    // 条件・コスト
    // -----------------------------------------------------------------------

    fun conditionToText(condition: Condition, master: MasterData): String = when (condition) {
        is CardExistsCondition -> {
            val noun = scopeToText(condition.scope, master, withCount = false)
            val counterWord = counter(condition.scope)
            if (condition.negate) {
                "${noun}が存在しない"
            } else {
                "${noun}が${condition.atLeast}${counterWord}以上存在する"
            }
        }

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
    }

    // -----------------------------------------------------------------------
    // カード全体
    // -----------------------------------------------------------------------

    /** カードの効果テキスト全体を組み立てる。効果を持たない場合は空文字。 */
    fun render(card: CardDef, master: MasterData): String {
        val effect = card.effect ?: return ""
        if (effect.isEmpty) return ""

        val lines = mutableListOf<String>()

        // 効果番号より前の共通指定。
        if (effect.locations.isNotEmpty()) {
            lines += "【場所】" + effect.locations.joinToString("、") { it.label }
        } else if (card.kind != CardKind.MONSTER) {
            // 魔法・罠で記述が省略されている場合は「フィールドで発動」。
            lines += "【場所】" + ActivationLocation.FIELD.label
        }
        if (effect.conditions.isNotEmpty()) {
            lines += "【条件】" + effect.conditions.joinToString("、かつ") {
                conditionToText(it, master)
            }
        }
        if (effect.costs.isNotEmpty()) {
            lines += "【コスト】" + effect.costs.joinToString("、") { costToText(it, master) }
        }

        // 各効果。
        effect.clauses.forEachIndexed { index, clause ->
            if (clause.actions.isEmpty()) return@forEachIndexed
            val sb = StringBuilder(circledNumber(index) + "：")

            if (clause.locations.isNotEmpty()) {
                sb.append("【場所】" + clause.locations.joinToString("、") { it.label } + " ")
            }
            if (clause.conditions.isNotEmpty()) {
                sb.append(
                    "【条件】" + clause.conditions.joinToString("、かつ") {
                        conditionToText(it, master)
                    } + " "
                )
            }
            if (clause.costs.isNotEmpty()) {
                sb.append(
                    "【コスト】" + clause.costs.joinToString("、") { costToText(it, master) } + " "
                )
            }
            if (card.kind == CardKind.MONSTER && clause.timing != EffectTiming.ON_ACTIVATE) {
                sb.append(clause.timing.label + "、")
            }

            sb.append(clause.actions.joinToString("。その後、") { actionToText(it, master) })
            sb.append("。")
            if (clause.oncePerTurn) sb.append("この効果は1ターンに1度しか使用できない。")
            lines += sb.toString()
        }

        return lines.joinToString("\n")
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
