package com.cardforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 永続効果で与えられる耐性の種類。 */
@Serializable
enum class ProtectionKind(val label: String) {
    OPPONENT_EFFECTS("相手の効果を受けない"),
    BATTLE_DESTRUCTION("戦闘では破壊されない"),
    EFFECT_DESTRUCTION("効果では破壊されない");

    companion object {
        val all: List<ProtectionKind> get() = entries
    }
}

/**
 * 【永続効果】発動を必要とせず、表側でフィールドにある限りずっと適用される効果。
 *
 * [scope] が null のときは、この効果を持つカード自身に適用する。
 */
@Serializable
sealed interface ContinuousEffect {
    val scope: CardScope?
}

/** 攻撃力・守備力を増減させる。 */
@Serializable
@SerialName("statBuff")
data class StatBuffEffect(
    override val scope: CardScope? = null,
    val stat: StatKind = StatKind.ATK,
    val amount: Int = 500
) : ContinuousEffect

/** 破壊されない・効果を受けないといった耐性を与える。 */
@Serializable
@SerialName("protection")
data class ProtectionEffect(
    override val scope: CardScope? = null,
    val kind: ProtectionKind = ProtectionKind.OPPONENT_EFFECTS
) : ContinuousEffect

/** 攻撃できなくする。 */
@Serializable
@SerialName("cannotAttack")
data class CannotAttackEffect(
    override val scope: CardScope? = null
) : ContinuousEffect
