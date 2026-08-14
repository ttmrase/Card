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
    val selection: SelectionMode = SelectionMode.CHOOSE
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
    val oncePerTurn: Boolean = false
)

@Serializable
data class EffectText(
    val locations: List<ActivationLocation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val costs: List<Cost> = emptyList(),
    val clauses: List<EffectClause> = emptyList()
) {
    val isEmpty: Boolean get() = clauses.all { it.actions.isEmpty() }

    /** [index] 番目の効果に適用される場所（共通指定を含む）。 */
    fun locationsFor(index: Int): List<ActivationLocation> {
        val own = clauses.getOrNull(index)?.locations.orEmpty()
        return if (own.isNotEmpty()) own else locations
    }

    fun conditionsFor(index: Int): List<Condition> =
        conditions + clauses.getOrNull(index)?.conditions.orEmpty()

    fun costsFor(index: Int): List<Cost> =
        costs + clauses.getOrNull(index)?.costs.orEmpty()
}
