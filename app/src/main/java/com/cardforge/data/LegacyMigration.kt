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
        var result = card
        if (result.continuous.isNotEmpty()) {
            val converted = result.continuous.map(::toClause)
            val effect = result.effect ?: EffectText()
            result = result.copy(
                continuous = emptyList(),
                effect = effect.copy(clauses = converted + effect.clauses)
            )
        }
        result.effect?.let { effect ->
            result = result.copy(effect = moveSummonRestrictions(effect))
        }
        return result
    }

    /**
     * 召喚制限は効果の述語ではなく【制限】として持つようにしたので、
     * 述語として書かれていた旧データを【制限】へ移す。
     *
     * 効果としての召喚制限は、効果を無効にされると掛からなくなってしまうため。
     */
    private fun moveSummonRestrictions(effect: EffectText): EffectText {
        if (effect.clauses.none { clause -> clause.actions.any { it is RestrictSummonAction } }) {
            return effect
        }

        val movedToCard = mutableListOf<SummonLock>()
        val clauses = effect.clauses.mapNotNull { clause ->
            val restrictions = clause.actions.filterIsInstance<RestrictSummonAction>()
            if (restrictions.isEmpty()) return@mapNotNull clause

            val locks = restrictions.map {
                SummonLock(it.who, it.summon, it.filters, it.except)
            }
            val rest = clause.actions.filterNot { it is RestrictSummonAction }
            // 「発動時」の制限だけの効果は、カード全体の【制限】に移す。
            if (rest.isEmpty() && clause.branches.isEmpty()) {
                movedToCard += locks
                null
            } else {
                clause.copy(actions = rest, summonLocks = clause.summonLocks + locks)
            }
        }
        return effect.copy(
            summonLocks = effect.summonLocks + movedToCard,
            clauses = clauses
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
