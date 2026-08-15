package com.cardforge.data

import com.cardforge.model.*

/**
 * 古い保存データを、いまの書き方に読み替える。
 *
 * 以前は永続効果を専用の欄で持っていたが、現在は効果の【発動タイプ】を
 * 「永続」にして、他の効果と同じ枠組みで書く。読み込んだ時点で変換するので、
 * 保存済みのカードもそのまま使える。
 */
object LegacyMigration {

    fun migrate(card: CardDef): CardDef {
        if (card.continuous.isEmpty()) return card

        val converted = card.continuous.map(::toClause)
        val effect = card.effect ?: EffectText()
        return card.copy(
            continuous = emptyList(),
            effect = effect.copy(clauses = converted + effect.clauses)
        )
    }

    private fun toClause(effect: ContinuousEffect): EffectClause {
        // 旧データの scope が null なら「このカード自身」を指していた。
        val scope = effect.scope ?: CardScope(selfOnly = true)
        val action = when (effect) {
            is StatBuffEffect -> ModifyStatAction(scope, effect.stat, effect.amount)
            is ProtectionEffect -> GrantProtectionAction(scope, effect.kind)
            is CannotAttackEffect -> PreventAttackAction(scope)
        }
        return EffectClause(mode = ActivationMode.CONTINUOUS, actions = listOf(action))
    }
}
