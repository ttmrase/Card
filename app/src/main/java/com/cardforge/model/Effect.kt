package com.cardforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// 目的語のフィルタ: 「闇属性の」「レベル4以下の」「『アララギ』の」など。
// ---------------------------------------------------------------------------

@Serializable
sealed interface CardFilter

@Serializable
@SerialName("kind")
data class KindFilter(val kind: CardKind) : CardFilter

@Serializable
@SerialName("attribute")
data class AttributeFilter(val attributeId: String) : CardFilter

@Serializable
@SerialName("race")
data class RaceFilter(val raceId: String) : CardFilter

@Serializable
@SerialName("category")
data class CategoryFilter(val categoryId: String) : CardFilter

@Serializable
@SerialName("level")
data class LevelFilter(val cmp: Cmp, val value: Int) : CardFilter

@Serializable
@SerialName("atk")
data class AtkFilter(val cmp: Cmp, val value: Int) : CardFilter

@Serializable
@SerialName("def")
data class DefFilter(val cmp: Cmp, val value: Int) : CardFilter

@Serializable
@SerialName("position")
data class PositionFilter(val position: Position) : CardFilter

@Serializable
@SerialName("name")
data class NameFilter(val text: String) : CardFilter

// ---------------------------------------------------------------------------
// 主語・目的語・修飾語をひとまとめにした「対象指定」。
// 例) 『相手フィールドの』『闇属性モンスター』『1体』を『選んで』
// ---------------------------------------------------------------------------

@Serializable
data class CardScope(
    val who: PlayerRef = PlayerRef.OPPONENT,
    val zone: ZoneType = ZoneType.MONSTER_ZONE,
    val filters: List<CardFilter> = emptyList(),
    val count: Int = 1,
    val selection: SelectionMode = SelectionMode.CHOOSE,
    /** この効果を持つカード自身だけを指す。true のとき他の指定は見ない。 */
    val selfOnly: Boolean = false,
    /**
     * 枚数を「〜の数だけ」のように別のカードの枚数から決める。
     * null なら [count] をそのまま使う。
     */
    val countSpec: ValueSpec? = null,
    /**
     * 「〜まで」。対象がその数に足りなくても、あるだけ処理する。
     * これを付けた指定は、発動時の「最後まで処理できるか」の判定でも数に数えない。
     */
    val upTo: Boolean = false
) {
    /** 枚数の指定。[countSpec] があればそちらが優先される。 */
    val countValue: ValueSpec get() = countSpec ?: FixedValue(count)
}

// ---------------------------------------------------------------------------
// 数値の指定。固定値のほか「条件を満たすカードの枚数×係数」を書ける。
// ---------------------------------------------------------------------------

@Serializable
sealed interface ValueSpec

@Serializable
@SerialName("fixedValue")
data class FixedValue(val value: Int = 0) : ValueSpec

/** [base] ＋ [scope] に当てはまるカードの枚数 × [multiplier]。 */
@Serializable
@SerialName("countValue")
data class CountValue(
    val scope: CardScope = CardScope(selection = SelectionMode.ALL),
    val multiplier: Int = 100,
    val base: Int = 0
) : ValueSpec

// ---------------------------------------------------------------------------
// 述語: 効果本体。
// ---------------------------------------------------------------------------

@Serializable
sealed interface Action

@Serializable
@SerialName("destroy")
data class DestroyAction(val scope: CardScope) : Action

@Serializable
@SerialName("banish")
data class BanishAction(val scope: CardScope) : Action

@Serializable
@SerialName("toHand")
data class ToHandAction(val scope: CardScope) : Action

@Serializable
@SerialName("toGrave")
data class ToGraveAction(val scope: CardScope) : Action

@Serializable
@SerialName("toDeck")
data class ToDeckAction(val scope: CardScope, val toBottom: Boolean = false) : Action

/** [scope] で選んだモンスターを [controller] のモンスターゾーンに特殊召喚する。 */
@Serializable
@SerialName("specialSummon")
data class SpecialSummonAction(
    val scope: CardScope,
    val position: Position = Position.ATTACK,
    val controller: PlayerRef = PlayerRef.SELF,
    /**
     * 選べる表示形式。2つ以上あれば、効果を処理するときにプレイヤーが選ぶ。
     * 空のときは [position] で固定。
     */
    val positionChoices: List<Position> = emptyList()
) : Action {
    val choices: List<Position> get() = positionChoices.ifEmpty { listOf(position) }
}

@Serializable
@SerialName("modifyStat")
data class ModifyStatAction(
    val scope: CardScope,
    val stat: StatKind,
    val delta: Int = 0,
    /** 指定があればこちらを使う。無ければ [delta] の固定値。 */
    val deltaValue: ValueSpec? = null
) : Action {
    val amountSpec: ValueSpec get() = deltaValue ?: FixedValue(delta)
}

@Serializable
@SerialName("changePosition")
data class ChangePositionAction(val scope: CardScope, val position: Position) : Action

/**
 * 「このターン、〜は…を特殊召喚できない」という召喚の制限。
 *
 * [except] が true なら「[filters] に当てはまるもの**以外**を出せない」、
 * false なら「[filters] に当てはまるものを出せない」という意味になる。
 * 制限はこのターンの間だけ続く。
 */
@Serializable
@SerialName("restrictSummon")
data class RestrictSummonAction(
    val who: PlayerRef = PlayerRef.SELF,
    val summon: SummonKind = SummonKind.SPECIAL,
    val filters: List<CardFilter> = emptyList(),
    val except: Boolean = true,
    /** 「このカードを発動するターン」と書きたいときに true。 */
    val fromActivation: Boolean = true
) : Action

/**
 * カードを相手に見せる。
 *
 * [RevealDuration.MOMENT] はその場で見せるだけ、それ以外は指定した長さのあいだ
 * 表にしたままにする（相手の画面にも中身が出る）。
 */
@Serializable
@SerialName("reveal")
data class RevealAction(
    val scope: CardScope = CardScope(
        who = PlayerRef.SELF,
        zone = ZoneType.HAND,
        selection = SelectionMode.ALL
    ),
    val duration: RevealDuration = RevealDuration.MOMENT
) : Action

@Serializable
@SerialName("draw")
data class DrawAction(
    val who: PlayerRef,
    val count: Int = 1,
    /** 「〜の数だけドローする」と書きたいときの枚数指定。 */
    val countValue: ValueSpec? = null
) : Action {
    val countSpec: ValueSpec get() = countValue ?: FixedValue(count)
}

@Serializable
@SerialName("damage")
data class DamageAction(
    val who: PlayerRef,
    val amount: Int = 0,
    val amountValue: ValueSpec? = null
) : Action {
    val amountSpec: ValueSpec get() = amountValue ?: FixedValue(amount)
}

@Serializable
@SerialName("recover")
data class RecoverAction(
    val who: PlayerRef,
    val amount: Int = 0,
    val amountValue: ValueSpec? = null
) : Action {
    val amountSpec: ValueSpec get() = amountValue ?: FixedValue(amount)
}

@Serializable
@SerialName("discard")
data class DiscardAction(
    val who: PlayerRef,
    val count: Int,
    val random: Boolean = false
) : Action

@Serializable
@SerialName("mill")
data class MillAction(val who: PlayerRef, val count: Int) : Action

/**
 * 耐性を与える。永続の効果に書けばその間ずっと、
 * 発動する効果に書けばそのターンの間だけ適用される。
 */
@Serializable
@SerialName("grantProtection")
data class GrantProtectionAction(
    val scope: CardScope,
    val kind: ProtectionKind = ProtectionKind.OPPONENT_EFFECTS
) : Action

/** 攻撃できなくする。永続なら常時、発動ならそのターンの間。 */
@Serializable
@SerialName("preventAttack")
data class PreventAttackAction(val scope: CardScope) : Action

/** 魔法・罠ゾーンに裏側でセットする。 */
@Serializable
@SerialName("setSpellTrap")
data class SetSpellTrapAction(val scope: CardScope) : Action

/** 魔法・罠ゾーンに表側で置く。発動はしないので、永続の効果だけが働く。 */
@Serializable
@SerialName("placeSpellTrap")
data class PlaceSpellTrapAction(val scope: CardScope) : Action

/** そのカードを発動する。 */
@Serializable
@SerialName("activateCard")
data class ActivateCardAction(val scope: CardScope) : Action

/** 相手が発動した効果を無効にし破壊する（罠カード向け）。 */
@Serializable
@SerialName("negate")
data object NegateAction : Action

// ---------------------------------------------------------------------------
// 【条件】
// ---------------------------------------------------------------------------

@Serializable
sealed interface Condition

/** 指定した領域に条件を満たすカードが [atLeast] 枚以上存在する（[negate] で「存在しない」）。 */
@Serializable
@SerialName("exists")
data class CardExistsCondition(
    val scope: CardScope,
    val atLeast: Int = 1,
    val negate: Boolean = false
) : Condition

@Serializable
@SerialName("life")
data class LifeCondition(val who: PlayerRef, val cmp: Cmp, val value: Int) : Condition

/**
 * 「〜した場合」というイベントの条件。
 *
 * この条件を持つ効果は、その出来事が起きたときにだけ発動する誘発効果になる。
 * 持たない効果は、自分のメインフェイズに手動で発動する起動効果として扱う。
 */
@Serializable
@SerialName("event")
data class EventCondition(
    val event: GameEventType,
    /** 出来事を起こしたカード（またはプレイヤー）の持ち主。 */
    val who: PlayerRef = PlayerRef.OPPONENT,
    /** 「このカードが〜した場合」にする。true のとき [who] と [filters] は見ない。 */
    val selfOnly: Boolean = false,
    val filters: List<CardFilter> = emptyList(),
    /**
     * 「相手の効果によって」のように、その出来事の原因を限定する。
     * [selfOnly] が true でもこの指定は見る。
     */
    val cause: CauseFilter = CauseFilter.ANY
) : Condition

/**
 * この効果を持つカード自身が、指定した領域にある場合。
 * 「送られた場所によって」のような場合分けに使う。
 */
@Serializable
@SerialName("selfZone")
data class SelfZoneCondition(val zones: List<ZoneType> = emptyList()) : Condition

/** 指定したフェイズにだけ発動できる。 */
@Serializable
@SerialName("phase")
data class PhaseCondition(val phases: List<Phase> = emptyList()) : Condition

@Serializable
@SerialName("zoneCount")
data class ZoneCountCondition(
    val who: PlayerRef,
    val zone: ZoneType,
    val cmp: Cmp,
    val value: Int
) : Condition

// ---------------------------------------------------------------------------
// 【コスト】
// ---------------------------------------------------------------------------

@Serializable
sealed interface Cost

@Serializable
@SerialName("payLife")
data class PayLifeCost(val amount: Int) : Cost

@Serializable
@SerialName("discardCost")
data class DiscardCost(val count: Int, val filters: List<CardFilter> = emptyList()) : Cost

/** 自分フィールドのモンスターをリリースする。 */
@Serializable
@SerialName("tributeCost")
data class TributeCost(val count: Int, val filters: List<CardFilter> = emptyList()) : Cost

@Serializable
@SerialName("banishGraveCost")
data class BanishFromGraveCost(
    val count: Int,
    val filters: List<CardFilter> = emptyList()
) : Cost

@Serializable
@SerialName("millCost")
data class MillCost(val count: Int) : Cost

/** コストで動かす先。 */
@Serializable
enum class MoveDestination(val label: String) {
    GRAVEYARD("墓地へ送る"),
    BANISHED("除外する"),
    HAND("手札に戻す"),
    DECK_TOP("デッキの一番上に戻す"),
    DECK_BOTTOM("デッキの一番下に戻す");

    companion object {
        val all: List<MoveDestination> get() = entries
    }
}

/**
 * 対象指定で選んだカードを動かすコスト。
 *
 * 効果と同じ対象指定が使えるので、「墓地のカードをデッキに戻す」
 * 「自分フィールドの闇属性モンスターをリリースする」なども書ける。
 */
@Serializable
@SerialName("moveCost")
data class MoveCost(
    val scope: CardScope,
    val destination: MoveDestination = MoveDestination.GRAVEYARD
) : Cost

/**
 * 発動するこのカード自身をコストにする。
 * 手札で発動するモンスターを「コストとして墓地へ送る」形にしたいときに使う。
 */
@Serializable
@SerialName("selfCost")
data class DiscardSelfCost(val banish: Boolean = false) : Cost

/** コストとしてカードを見せる（公開する）。 */
@Serializable
@SerialName("revealCost")
data class RevealCost(
    val scope: CardScope = CardScope(
        who = PlayerRef.SELF,
        zone = ZoneType.HAND,
        selection = SelectionMode.ALL
    ),
    val duration: RevealDuration = RevealDuration.MOMENT
) : Cost

// ---------------------------------------------------------------------------
// 【制限】
// ---------------------------------------------------------------------------

/**
 * 発動回数の制限。「1ターンに[times]度まで」を [scope] の単位で数える。
 *
 * 効果番号より前に書いた制限はカードの全ての効果をまとめて数え、
 * 効果番号の直後に書いた制限はその番号の効果だけを数える。
 */
@Serializable
data class UsageLimit(
    val scope: LimitScope = LimitScope.THIS_CARD,
    val times: Int = 1,
    /** [LimitScope.CATEGORY] のとき数える対象のカテゴリ。null ならこのカードのカテゴリ。 */
    val categoryId: String? = null,
    /**
     * 掛ける効果の番号（0 始まり）。空なら全ての効果に掛かる。
     * 効果番号より前に書く制限でだけ意味を持つ。
     */
    val clauseIndices: List<Int> = emptyList(),
    /**
     * [LimitApplies.TOGETHER] なら対象の効果をまとめて数え、
     * [LimitApplies.EACH] なら効果ごとに別々に数える。
     */
    val applies: LimitApplies = LimitApplies.TOGETHER
) {
    /** [index] 番目の効果に掛かるか。 */
    fun coversClause(index: Int): Boolean =
        clauseIndices.isEmpty() || index in clauseIndices
}

// ---------------------------------------------------------------------------
// 効果テキスト本体。
//
// EffectText 直下の場所・条件・コストは効果番号より前に書かれたもので、
// カードの全ての効果に適用される。EffectClause 側のものは、その番号の
// 効果にのみ適用される。
// ---------------------------------------------------------------------------

/**
 * 場合分けの1項目。[conditions] を満たしたときに [actions] を処理する。
 * カードテキストでは「●条件：効果」として表示される。
 */
@Serializable
data class EffectBranch(
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList()
)

@Serializable
data class EffectClause(
    val timing: EffectTiming = EffectTiming.ON_ACTIVATE,
    val locations: List<ActivationLocation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val costs: List<Cost> = emptyList(),
    val actions: List<Action> = emptyList(),
    /** 場合分け。共通の [actions] を処理したあとに評価する。 */
    val branches: List<EffectBranch> = emptyList(),
    val branchMode: BranchMode = BranchMode.FIRST_MATCH,
    /** 任意発動か強制発動か。 */
    val mode: ActivationMode = ActivationMode.OPTIONAL,
    /** この効果だけの制限。 */
    val limits: List<UsageLimit> = emptyList(),
    /** この効果だけの【発動後】。null ならカード共通の指定に従う。 */
    val afterActivation: AfterActivation? = null,
    /** 旧データ互換。制限欄が空でこれが true なら「1ターンに1度」として扱う。 */
    val oncePerTurn: Boolean = false
)

/** 効果に、処理すべき内容があるか。 */
val EffectClause.hasWork: Boolean
    get() = actions.isNotEmpty() || branches.any { it.actions.isNotEmpty() }

@Serializable
data class EffectText(
    val locations: List<ActivationLocation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val costs: List<Cost> = emptyList(),
    /** 全ての効果をまとめて数える制限。 */
    val limits: List<UsageLimit> = emptyList(),
    /** 【発動後】。null なら [AfterActivation.DEFAULT]（墓地へ送る）。 */
    val afterActivation: AfterActivation? = null,
    val clauses: List<EffectClause> = emptyList()
) {
    val isEmpty: Boolean get() = clauses.none { it.hasWork }

    /** [index] 番目の効果に適用される場所（共通指定を含む）。 */
    fun locationsFor(index: Int): List<ActivationLocation> {
        val own = clauses.getOrNull(index)?.locations.orEmpty()
        return if (own.isNotEmpty()) own else locations
    }

    fun conditionsFor(index: Int): List<Condition> =
        conditions + clauses.getOrNull(index)?.conditions.orEmpty() + legacyTrigger(index)

    /** 旧データの timing を、同じ意味のイベント条件として読み替える。 */
    private fun legacyTrigger(index: Int): List<Condition> {
        val clause = clauses.getOrNull(index) ?: return emptyList()
        if (clause.conditions.any { it is EventCondition }) return emptyList()
        if (conditions.any { it is EventCondition }) return emptyList()
        val event = when (clause.timing) {
            EffectTiming.ON_SUMMON -> GameEventType.SUMMONED
            EffectTiming.ON_DESTROYED -> GameEventType.DESTROYED
            EffectTiming.ON_ATTACK -> GameEventType.ATTACK_DECLARED
            EffectTiming.IGNITION, EffectTiming.ON_ACTIVATE -> return emptyList()
        }
        return listOf(EventCondition(event = event, selfOnly = true))
    }

    fun costsFor(index: Int): List<Cost> =
        costs + clauses.getOrNull(index)?.costs.orEmpty()

    /** [index] 番目の効果にだけ掛かる制限（旧データの oncePerTurn を含む）。 */
    fun clauseLimitsFor(index: Int): List<UsageLimit> {
        val clause = clauses.getOrNull(index) ?: return emptyList()
        if (clause.limits.isNotEmpty()) return clause.limits
        return if (clause.oncePerTurn) listOf(UsageLimit(LimitScope.THIS_CARD, 1))
        else emptyList()
    }

    /**
     * [index] 番目の効果の【発動後】。
     * 指定が無い場合、魔法・罠は「墓地へ送る」、モンスターは「そのまま残す」。
     */
    fun afterActivationFor(index: Int, kind: CardKind): AfterActivation =
        clauses.getOrNull(index)?.afterActivation
            ?: afterActivation
            ?: defaultAfterActivation(kind)

    /** [index] 番目の効果が発動できるフェイズ。指定が無ければ空。 */
    fun phasesFor(index: Int): List<Phase> =
        conditionsFor(index).filterIsInstance<PhaseCondition>().flatMap { it.phases }

    /** [index] 番目の効果が持つイベント条件（誘発条件）。 */
    fun triggersFor(index: Int): List<EventCondition> =
        conditionsFor(index).filterIsInstance<EventCondition>()

    /** イベント条件を持つ効果は誘発効果、持たない効果は起動効果。 */
    fun isTriggered(index: Int): Boolean =
        clauses.getOrNull(index)?.mode?.isStandalone == true && triggersFor(index).isNotEmpty()

    /** 永続の効果は発動せず、【場所】にある限り適用される。 */
    fun isContinuous(index: Int): Boolean =
        clauses.getOrNull(index)?.mode?.isContinuous == true

    /** そのカードの発動時にだけ処理される効果。 */
    fun isOnActivation(index: Int): Boolean =
        clauses.getOrNull(index)?.mode?.isOnActivation == true

    /** そのカードの発動時に処理する効果の番号。 */
    fun onActivationClauses(): List<Int> =
        clauses.indices.filter { isOnActivation(it) && clauses[it].hasWork }

    /** 発動時処理か永続の効果があれば、そのカード自体を「発動」できる。 */
    fun supportsCardActivation(): Boolean =
        clauses.indices.any { (isOnActivation(it) || isContinuous(it)) }

    /** カードの発動そのものに対する【発動後】。 */
    fun afterActivationForCard(kind: CardKind): AfterActivation =
        afterActivation ?: defaultAfterActivation(kind)

    companion object {
        fun defaultAfterActivation(kind: CardKind): AfterActivation =
            if (kind == CardKind.MONSTER) AfterActivation.STAY_ON_FIELD
            else AfterActivation.TO_GRAVE
    }
}
