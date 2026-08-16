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

    /** 【…】の欄どうしの区切り。 */
    const val SECTION_SEPARATOR = " ／ "

    /** 【…】の欄と、効果そのものの区切り。 */
    const val EFFECT_ARROW = " ⇒ "

    fun circledNumber(index: Int): String = CIRCLED.getOrElse(index) { "(${index + 1})" }

    // -----------------------------------------------------------------------
    // 対象指定（主語・目的語・修飾語）
    // -----------------------------------------------------------------------

    private fun zonePrefix(who: PlayerRef, zones: List<ZoneType>): String {
        val zone = zones.first()
        val label = zones.joinToString("または") { it.label }
        val needsParticle = zones.size > 1 ||
            zone.isHidden || zone == ZoneType.GRAVEYARD || zone == ZoneType.BANISHED ||
            who == PlayerRef.BOTH
        return if (needsParticle) "${who.label}の${label}の" else "${who.label}${label}の"
    }

    /** 「体」か「枚」か。モンスターを指しているなら「体」。 */
    private fun counter(scope: CardScope): String {
        val kind = scope.filters.filterIsInstance<KindFilter>().firstOrNull()?.kind
        return when {
            kind == CardKind.MONSTER -> "体"
            kind != null -> "枚"
            scope.zoneList.all { it == ZoneType.MONSTER_ZONE } -> "体"
            else -> "枚"
        }
    }

    /** フィルタ列を「『アララギ』レベル4以下の闇属性ドラゴン族モンスター」のような名詞句にする。 */
    fun filtersToNoun(
        filters: List<CardFilter>,
        master: MasterData,
        fallbackZone: ZoneType? = null
    ): String {
        // 「または」は、他の条件と組み合わせて1つずつ書き出す。
        val anyFilter = filters.filterIsInstance<AnyFilter>().firstOrNull()
        if (anyFilter != null && anyFilter.filters.isNotEmpty()) {
            val others = filters.filterNot { it === anyFilter }
            return anyFilter.filters.joinToString("または") { alternative ->
                filtersToNoun(others + alternative, master, fallbackZone)
            }
        }

        val sb = StringBuilder()

        filters.filterIsInstance<NotFilter>().forEach {
            sb.append(filtersToNoun(listOf(it.filter), master) + "以外の")
        }
        filters.filterIsInstance<AffectedNameFilter>().forEach {
            sb.append(if (it.exclude) "直前に扱ったカードと同名でない" else "直前に扱ったカードと同名の")
        }
        filters.filterIsInstance<SummonedByThisFilter>().forEach {
            sb.append(if (it.enabled) "このカードの効果によって特殊召喚された" else "それ以外の")
        }
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
        scope.triggerCard?.let {
            return when (it) {
                TriggerCardRef.EVENT_CARD -> "その出来事の対象になったカード"
                TriggerCardRef.SOURCE_CARD -> "その相手のカード"
            }
        }
        val prefix = zonePrefix(scope.who, scope.zoneList)
        val noun = filtersToNoun(scope.filters, master, scope.mainZone)
        if (scope.selection == SelectionMode.ALL) return "${prefix}全ての$noun"
        if (!withCount) return "$prefix$noun"

        // 「〜の数だけ」と書く場合は、数の話を先に出したほうが読みやすい。
        val spec = scope.countSpec
        if (spec is CountValue) {
            val tail = if (scope.upTo) "まで、" else "だけ、"
            return countSourceToText(spec, master) + tail + prefix + noun
        }

        val amount = if (spec is FixedValue) spec.value else scope.count
        val limit = if (scope.upTo) "まで" else ""
        return "$prefix$noun$amount${counter(scope)}$limit"
    }

    /** 「自分の手札の「VALIS」魔法カードの数」のような、数える対象の表現。 */
    private fun countSourceToText(spec: CountValue, master: MasterData): String = buildString {
        val scope = spec.scope.copy(selection = SelectionMode.CHOOSE)
        append(scopeToText(scope, master, withCount = false))
        append("の数")
        if (spec.multiplier != 1) append("×${spec.multiplier}")
        if (spec.base != 0) append("＋${spec.base}")
    }

    /** ドローなど、枚数そのものを書きたいときの表現。 */
    private fun countSpecToText(spec: ValueSpec, master: MasterData): String = when (spec) {
        is FixedValue -> spec.value.toString()
        is CountValue -> countSourceToText(spec, master) + "だけ"
        else -> valueToText(spec, master) + "だけ"
    }

    /**
     * 「3枚」「〜の数だけ」のように枚数を書く。
     * 数を参照する指定では「〜だけ」で言い切るので、助数詞は付けない。
     */
    private fun amountText(spec: ValueSpec, counterWord: String, master: MasterData): String =
        if (spec is FixedValue) "${spec.value}$counterWord"
        else countSpecToText(spec, master)

    /** 「を選んで」「をランダムに」など、対象と述語をつなぐ部分。 */
    private fun selectionParticle(scope: CardScope): String {
        // 「このカード」「そのカード」を指しているときは選ぶ余地が無い。
        if (scope.selfOnly || scope.triggerCard != null) return "を"
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

    /** 「この効果の発動に対して〜はカードの効果を発動できない」という【制限】の文。 */
    fun noResponseToText(from: PlayerRef): String =
        "この効果の発動に対して${from.label}はカードの効果を発動できない"

    /** 「〜以外のモンスター」のような、制限の対象を表す名詞。 */
    fun restrictionTarget(
        kind: RestrictionKind,
        filters: List<CardFilter>,
        except: Boolean,
        master: MasterData
    ): String {
        if (filters.isEmpty()) return kind.defaultNoun
        val noun = filtersToNoun(filters, master, ZoneType.MONSTER_ZONE)
        return if (except) "${noun}以外の${kind.defaultNoun}" else noun
    }

    /** 「相手はモンスターを特殊召喚できない」という制限の一文。 */
    fun restrictionSentence(
        who: String,
        kind: RestrictionKind,
        filters: List<CardFilter>,
        except: Boolean,
        master: MasterData
    ): String = kind.template
        .replace("{who}", who)
        .replace("{target}", restrictionTarget(kind, filters, except, master))

    /** 「相手のモンスターは直接攻撃できる」という許可の一文。 */
    fun permissionSentence(
        who: String,
        kind: PermissionKind,
        filters: List<CardFilter>,
        except: Boolean,
        master: MasterData
    ): String {
        val target =
            if (filters.isEmpty()) kind.defaultNoun
            else {
                val noun = filtersToNoun(filters, master, ZoneType.MONSTER_ZONE)
                if (except) "${noun}以外の${kind.defaultNoun}" else noun
            }
        return kind.template.replace("{who}", who).replace("{target}", target)
    }

    /** 「このカードを発動するターン、〜できない」という【制限】の文。 */
    fun playLockToText(lock: PlayLock, master: MasterData): String =
        "このカードを発動するターン、" +
            restrictionSentence(lock.who.label, lock.kind, lock.filters, lock.except, master)

    /** 旧データ用。述語として書かれていた召喚制限の文。 */
    fun restrictSummonToText(action: RestrictSummonAction, master: MasterData): String {
        val kind = when (action.summon) {
            SummonKind.NORMAL -> RestrictionKind.NORMAL_SUMMON
            SummonKind.SPECIAL -> RestrictionKind.SPECIAL_SUMMON
            SummonKind.ANY -> RestrictionKind.ANY_SUMMON
        }
        return playLockToText(PlayLock(action.who, kind, action.filters, action.except), master)
    }

    /** 「手札を相手に見せる」という文。 */
    fun revealToText(scope: CardScope, duration: RevealDuration, master: MasterData): String {
        val what = scopeToText(scope, master)
        val tail = when (duration) {
            RevealDuration.MOMENT -> "を相手に見せる"
            RevealDuration.TURN -> "を相手に見せ、このターンの間公開したままにする"
            RevealDuration.PERMANENT -> "を相手に見せ、公開したままにする"
        }
        return "$what$tail"
    }

    /** 素材を送って特殊召喚する文。 */
    fun materialSummonToText(action: MaterialSummonAction, master: MasterData): String {
        val target = scopeToText(action.summon, master)
        val material = scopeToText(action.material, master, withCount = false)
        val how = when (action.requirement) {
            MaterialRequirement.LEVEL_EXACT ->
                "レベルの合計がそのモンスターのレベルとぴったり同じになるように"

            MaterialRequirement.LEVEL_OR_MORE ->
                "レベルの合計がそのモンスターのレベル以上になるように"

            MaterialRequirement.COUNT -> "${action.count.coerceAtLeast(1)}枚"
        }
        val positions = action.choices.joinToString("または") { it.label }
        return "${target}を、${material}を${how}${action.destination.label}ことで、" +
            "${positions}で特殊召喚する"
    }

    /** 「相手の効果を受けない」のように、誰の効果に対する耐性かを書く。 */
    fun protectionLabel(action: GrantProtectionAction): String {
        if (!action.kind.usesSide) return action.kind.label
        val side = when (action.from) {
            PlayerRef.BOTH -> "お互いの"
            PlayerRef.SELF -> "自分の"
            PlayerRef.OPPONENT -> "相手の"
        }
        return side + action.kind.label
    }

    /** 「〜は次の効果を得る」という文。 */
    fun grantEffectToText(action: GrantEffectAction, master: MasterData): String {
        val who = scopeToText(action.scope, master, withCount = false)
        val body = grantedClauseToText(action.granted, master)
        return "${who}は次の効果を得る「$body」"
    }

    /** 与えられる効果の中身。番号を付けずに1文で書く。 */
    fun grantedClauseToText(clause: EffectClause, master: MasterData): String {
        if (!clause.hasWork) return "（効果が未設定）"
        val sb = StringBuilder()
        if (clause.conditions.isNotEmpty()) {
            sb.append(
                orderedConditions(clause.conditions).joinToString("、かつ") {
                    conditionToText(it, master)
                }
            ).append("、")
        }
        val body = if (clause.mode.isContinuous) {
            joinSteps(clause) { continuousActionToText(it, master) }
        } else {
            joinSteps(clause) { actionToText(it, master) }
        }
        sb.append(body).append("。")
        appendBranches(sb, clause, master)
        return sb.toString()
    }

    fun actionToText(action: Action, master: MasterData): String = when (action) {
        is GrantEffectAction -> grantEffectToText(action, master)

        is PermitAction ->
            "${action.duration.label}、" + permissionSentence(
                action.who.label, action.kind, action.filters, action.except, master
            )

        is RestrictAction ->
            "${action.duration.label}、" + restrictionSentence(
                action.who.label, action.kind, action.filters, action.except, master
            )

        is AddCounterAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "${master.counterName(action.counterId)}を${action.amount}個乗せる"

        is RemoveCounterAction ->
            scopeToText(action.scope, master) + "から" +
                "${master.counterName(action.counterId)}を${action.amount}個取り除く"

        is CreateTokenAction -> {
            val positions = action.choices.joinToString("または") { it.label }
            "${action.controller.label}のモンスターゾーンにトークンを" +
                "${amountText(action.countSpec, "体", master)}、" +
                "${positions}で特殊召喚する"
        }

        is ReplaceDestinationAction ->
            scopeToText(action.scope, master, withCount = false) +
                "が墓地へ送られる場合、代わりに${action.to.label}"

        is AdvancePhaseAction -> action.kind.label.removeSuffix("する") + "する"

        is MaterialSummonAction -> materialSummonToText(action, master)

        is RestrictSummonAction -> restrictSummonToText(action, master)

        is RevealAction -> revealToText(action.scope, action.duration, master)

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

        is DrawAction ->
            "${action.who.label}はカードを${amountText(action.countSpec, "枚", master)}ドローする"

        is DamageAction ->
            "${action.who.label}に${valueToText(action.amountSpec, master)}ポイントのダメージを与える"

        is RecoverAction ->
            "${action.who.label}のライフを${valueToText(action.amountSpec, master)}ポイント回復する"

        is DiscardAction -> {
            val how = if (action.random) "ランダムに" else ""
            "${action.who.label}は手札を$how${amountText(action.countSpec, "枚", master)}捨てる"
        }

        is MillAction ->
            "${action.who.label}のデッキの上からカードを" +
                "${amountText(action.countSpec, "枚", master)}墓地へ送る"

        is SetSpellTrapAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "魔法・罠ゾーンにセットする"

        is PlaceSpellTrapAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) +
                "魔法・罠ゾーンに表側で置く"

        is ActivateCardAction ->
            scopeToText(action.scope, master) + selectionParticle(action.scope) + "発動する"

        is GrantProtectionAction ->
            scopeToText(action.scope, master, withCount = false) + "は" + protectionLabel(action)

        is PreventAttackAction ->
            scopeToText(action.scope, master, withCount = false) + "は攻撃できない"

        NegateAction -> "相手の効果の発動を無効にし、そのカードを破壊する"
    }

    // -----------------------------------------------------------------------
    // 条件・コスト
    // -----------------------------------------------------------------------

    /** 「相手の闇属性モンスターが破壊された場合」のようなイベント条件の文。 */
    fun eventConditionToText(condition: EventCondition, master: MasterData): String {
        val by = buildString {
            append(condition.cause.prefix)
            // 「罠カードの対象に取られた」のように、起こした側を書く。
            if (condition.sourceFilters.isNotEmpty()) {
                append(filtersToNoun(condition.sourceFilters, master, ZoneType.FIELD))
                append("によって")
            }
        }
        val tail = condition.window.suffix
        if (condition.selfOnly) return "このカードが${by}${condition.event.label}$tail"
        if (condition.event.isPlayerEvent) {
            return "${condition.who.label}が${by}${condition.event.label}$tail"
        }
        val noun = filtersToNoun(condition.filters, master, ZoneType.FIELD)
        return "${condition.who.label}の${noun}が${by}${condition.event.label}$tail"
    }

    fun conditionToText(condition: Condition, master: MasterData): String = when (condition) {
        is EventCondition -> eventConditionToText(condition, master)

        is CounterCondition ->
            scopeToText(condition.scope, master, withCount = false) +
                "に${master.counterName(condition.counterId)}が" +
                "${condition.value}個${condition.cmp.label}乗っている"

        is AnyOfCondition ->
            if (condition.conditions.isEmpty()) "（条件が未設定）"
            else condition.conditions.joinToString("、または") { conditionToText(it, master) }

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

        is RevealCost -> revealToText(cost.scope, cost.duration, master)

        is CounterCost ->
            scopeToText(cost.scope, master, withCount = false) +
                "の${master.counterName(cost.counterId)}を${cost.amount}個取り除く"
    }

    /** 数値の指定を文にする。「自分の墓地のモンスターの数×100」など。 */
    fun valueToText(spec: ValueSpec, master: MasterData): String = when (spec) {
        is FixedValue -> spec.value.toString()

        is AffectedCountValue -> buildString {
            append("直前の処理で扱ったカードの数")
            if (spec.multiplier != 1) append("×${spec.multiplier}")
            if (spec.base != 0) append("＋${spec.base}")
        }

        is CounterValue -> buildString {
            append(scopeToText(spec.scope, master, withCount = false))
            append("に乗っている${master.counterName(spec.counterId)}の数")
            if (spec.multiplier != 1) append("×${spec.multiplier}")
            if (spec.base != 0) append("＋${spec.base}")
        }

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
    fun limitToText(
        limit: UsageLimit,
        master: MasterData,
        cardWide: Boolean,
        /** 効果番号の書き方。番号を振らない効果があるカードでずれないようにする。 */
        numbering: (Int) -> String = ::circledNumber
    ): String {
        val times = limit.times.coerceAtLeast(1)
        val numbers = limit.clauseIndices.sorted().joinToString("") { numbering(it) }

        // 数える単位だけで意味が通る場合は、主語を書かずに簡潔にする。
        val head = when {
            !cardWide -> "この効果は"
            limit.clauseIndices.isNotEmpty() && limit.applies == LimitApplies.EACH ->
                "${numbers}はそれぞれ"

            limit.clauseIndices.isNotEmpty() && limit.applies == LimitApplies.ONLY_ONE_KIND ->
                "${numbers}のうちいずれか1つだけ、"

            limit.clauseIndices.isNotEmpty() -> "${numbers}は合わせて"
            limit.applies == LimitApplies.EACH -> "それぞれの効果は"
            limit.applies == LimitApplies.ONLY_ONE_KIND -> "いずれか1つの効果だけ、"
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
        val cardLimits = effect.limits.map { limitToText(it, master, cardWide = true) } +
            effect.playLocks.map { playLockToText(it, master) } +
            listOfNotNull(effect.noResponseFrom?.let { noResponseToText(it) })
        if (cardLimits.isNotEmpty()) {
            lines += "【制限】" + cardLimits.joinToString("、")
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

            // 効果そのものの前に置く【…】の欄。区切りを入れて、効果本体と分ける。
            val prefixes = mutableListOf<String>()
            if (clause.locations.isNotEmpty()) {
                prefixes += "【場所】" + clause.locations.joinToString("、") { it.label }
            }
            val clauseConditions = orderedConditions(effect.conditionsFor(index) - effect.conditions)
            if (clauseConditions.isNotEmpty()) {
                prefixes += "【条件】" + clauseConditions.joinToString("、かつ") {
                    conditionToText(it, master)
                }
            }
            if (clause.costs.isNotEmpty()) {
                prefixes += "【コスト】" + clause.costs.joinToString("、") { costToText(it, master) }
            }
            val clauseLocks = clause.playLocks.map { playLockToText(it, master) } +
                listOfNotNull(clause.noResponseFrom?.let { noResponseToText(it) })
            if (clauseLocks.isNotEmpty()) {
                prefixes += "【制限】" + clauseLocks.joinToString("、")
            }
            // 効果番号ごとの【発動後】は、明示されていれば常に書く。
            clause.afterActivation?.let { prefixes += "【発動後】" + it.label }
            if (clause.quick) prefixes += "【誘発即時】"
            if (prefixes.isNotEmpty()) {
                sb.append(prefixes.joinToString(SECTION_SEPARATOR)).append(EFFECT_ARROW)
            }

            if (effect.isOnActivation(index)) {
                // 召喚制限だけの効果は、それ自体が「発動するターン」の文になっている。
                val onlyRestrictions = clause.actions.isNotEmpty() &&
                    clause.actions.all { it is RestrictSummonAction }
                if (!onlyRestrictions) sb.append("このカードの発動時に、")
                sb.append(joinSteps(clause) { actionToText(it, master) })
            } else if (effect.isContinuous(index)) {
                // 「このカードがフィールドに存在する限り、〜」という書き出しにする。
                val where = effectiveLocationLabel(effect, index)
                sb.append("このカードが${where}に存在する限り、")
                sb.append(
                    stepText(
                        clause.actions, emptyList(), clause.linkedSteps,
                        ActivationMode.MANDATORY, false
                    ) { continuousActionToText(it, master) }.replace("。その後、", "。また、")
                )
            } else if (clause.actions.isNotEmpty()) {
                sb.append(
                    joinSteps(clause, triggered = effect.isTriggered(index)) {
                        actionToText(it, master)
                    }
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
                val what = protectionLabel(action)
                if (who.isEmpty()) what else "${who}は$what"
            }

            is PermitAction -> permissionSentence(
                action.who.label, action.kind, action.filters, action.except, master
            )

            is RestrictAction -> restrictionSentence(
                action.who.label, action.kind, action.filters, action.except, master
            )

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
            sb.append(joinSteps(branch) { actionToText(it, master) })
            sb.append("。")
        }
    }

    /** イベント条件を先に読ませる。 */
    private fun orderedConditions(conditions: List<Condition>): List<Condition> =
        conditions.filterIsInstance<EventCondition>() +
            conditions.filterNot { it is EventCondition }

    /**
     * 処理のまとまりごとに文をつなぐ。
     *
     * まとめた処理は「〜し、」でつながり、まとまりが任意なら最後に
     * 「〜することができる」が付く。まとまりどうしは「。その後、」でつなぐ。
     */
    private fun joinSteps(
        clause: EffectClause,
        triggered: Boolean = false,
        text: (Action) -> String
    ): String = stepText(
        clause.actions, clause.optionalSteps, clause.linkedSteps, clause.mode, triggered, text
    )

    private fun joinSteps(
        branch: EffectBranch,
        text: (Action) -> String
    ): String = stepText(
        branch.actions, branch.optionalSteps, branch.linkedSteps,
        ActivationMode.MANDATORY, false, text
    )

    private fun stepText(
        actions: List<Action>,
        optionalSteps: List<Int>,
        linkedSteps: List<Int>,
        mode: ActivationMode,
        triggered: Boolean,
        text: (Action) -> String
    ): String {
        val units = stepUnits(actions.size, optionalSteps, linkedSteps)
        return units.mapIndexed { position, unit ->
            val body = joinLinked(unit.indices.map { text(actions[it]) })
            when {
                unit.optional -> optionalStepText(body)
                // 最後の文だけ「〜できる（任意）／〜する（強制）」を書き分ける。
                position == units.lastIndex && triggered -> applyMode(body, mode)
                else -> body
            }
        }.joinToString("。その後、")
    }

    /**
     * まとめた処理をつなぐ。「破壊する」＋「除外する」→「破壊し、除外する」。
     * 最後の1文はそのまま、それより前は連用形にする。
     */
    fun joinLinked(texts: List<String>): String =
        texts.mapIndexed { index, text ->
            if (index == texts.lastIndex) text else linkedForm(text) + "、"
        }.joinToString("")

    /** 「〜し、」とつなげるための連用形。 */
    private fun linkedForm(text: String): String = when {
        text.endsWith("する") -> text.dropLast(2) + "し"
        ICHIDAN_TAILS.any { text.endsWith(it) } -> text.dropLast(1)
        text.endsWith("る") -> text.dropLast(1) + "り"
        text.endsWith("す") -> text.dropLast(1) + "し"
        text.endsWith("く") -> text.dropLast(1) + "き"
        text.endsWith("ぐ") -> text.dropLast(1) + "ぎ"
        text.endsWith("む") -> text.dropLast(1) + "み"
        text.endsWith("ぶ") -> text.dropLast(1) + "び"
        text.endsWith("つ") -> text.dropLast(1) + "ち"
        text.endsWith("う") -> text.dropLast(1) + "い"
        else -> text
    }

    /** 「る」を落とすだけでよい一段動詞の語尾。 */
    private val ICHIDAN_TAILS =
        listOf("える", "ける", "せる", "てる", "める", "れる", "ねる", "べる", "げる", "でる")

    /** 「〜することができる」と書く、任意の処理の文。 */
    fun optionalStepText(text: String): String = text + "ことができる"

    /** 述語の語尾を、任意発動なら「できる」に置き換える。 */
    private fun applyMode(text: String, mode: ActivationMode): String {
        if (mode == ActivationMode.MANDATORY) return text
        return toOptional(text)
    }

    private fun toOptional(text: String): String {
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
