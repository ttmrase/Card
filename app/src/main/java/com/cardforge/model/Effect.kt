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
    val selfOnly: Boolean = false
)

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
    val controller: PlayerRef = PlayerRef.SELF
) : Action

@Serializable
@SerialName("modifyStat")
data class ModifyStatAction(
    val scope: CardScope,
    val stat: StatKind,
    val delta: Int
) : Action

@Serializable
@SerialName("changePosition")
data class ChangePositionAction(val scope: CardScope, val position: Position) : Action

@Serializable
@SerialName("draw")
data class DrawAction(val who: PlayerRef, val count: Int) : Action

@Serializable
@SerialName("damage")
data class DamageAction(val who: PlayerRef, val amount: Int) : Action

@Serializable
@SerialName("recover")
data class RecoverAction(val who: PlayerRef, val amount: Int) : Action

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
    val filters: List<CardFilter> = emptyList()
) : Condition

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

/**
 * 発動するこのカード自身をコストにする。
 * 手札で発動するモンスターを「コストとして墓地へ送る」形にしたいときに使う。
 */
@Serializable
@SerialName("selfCost")
data class DiscardSelfCost(val banish: Boolean = false) : Cost

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
    val categoryId: String? = null
)

// ---------------------------------------------------------------------------
// 効果テキスト本体。
//
// EffectText 直下の場所・条件・コストは効果番号より前に書かれたもので、
// カードの全ての効果に適用される。EffectClause 側のものは、その番号の
// 効果にのみ適用される。
// ---------------------------------------------------------------------------

@Serializable
data class EffectClause(
    val timing: EffectTiming = EffectTiming.ON_ACTIVATE,
    val locations: List<ActivationLocation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val costs: List<Cost> = emptyList(),
    val actions: List<Action> = emptyList(),
    /** 任意発動か強制発動か。 */
    val mode: ActivationMode = ActivationMode.OPTIONAL,
    /** この効果だけの制限。 */
    val limits: List<UsageLimit> = emptyList(),
    /** この効果だけの【発動後】。null ならカード共通の指定に従う。 */
    val afterActivation: AfterActivation? = null,
    /** 旧データ互換。制限欄が空でこれが true なら「1ターンに1度」として扱う。 */
    val oncePerTurn: Boolean = false
)

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
    val isEmpty: Boolean get() = clauses.all { it.actions.isEmpty() }

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
        clauses.indices.filter { isOnActivation(it) && clauses[it].actions.isNotEmpty() }

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
