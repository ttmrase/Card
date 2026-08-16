package com.cardforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 永続効果で与えられる耐性の種類。 */
@Serializable
enum class ProtectionKind(val label: String, val usesSide: Boolean = true) {
    /** 効果を受けない。誰の効果かは [GrantProtectionAction.from] で決める。 */
    OPPONENT_EFFECTS("効果を受けない"),
    BATTLE_DESTRUCTION("戦闘では破壊されない", usesSide = false),
    EFFECT_DESTRUCTION("効果では破壊されない"),
    NOT_TARGETED("効果の対象にならない");

    companion object {
        val all: List<ProtectionKind> get() = entries
    }
}

/**
 * 旧データ互換。以前は永続効果を専用の欄で持っていた。
 * 現在は効果の【発動タイプ】を「永続」にして、通常の効果と同じ枠組みで書く。
 * 保存済みのカードを読み込むときに、この形から効果へ変換する。
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
