package com.cardforge.data

import com.cardforge.model.*
import kotlinx.serialization.Serializable

/**
 * 書き出しファイルの中身。
 *
 * 属性・種族・カテゴリは、参照しているものだけを一緒に書き出す。
 * イラストは [images] に Base64 で埋め込むので、ファイル1つで受け渡せる。
 */
@Serializable
data class CardExchange(
    val formatVersion: Int = 1,
    val appName: String = "CardForge",
    val master: MasterData = MasterData(),
    val cards: List<CardDef> = emptyList(),
    val decks: List<Deck> = emptyList(),
    /** カードが指しているイラストのパス → Base64 データ。 */
    val images: Map<String, String> = emptyMap()
)

/** 取り込み結果の内訳。 */
data class ImportSummary(
    val addedCards: Int,
    val updatedCards: Int,
    val addedDecks: Int,
    val addedMasterEntries: Int
) {
    val total: Int get() = addedCards + updatedCards + addedDecks
}

/**
 * 書き出したカードを別の端末で取り込めるようにするための ID の対応付け。
 *
 * 属性・種族・カテゴリは、同じ名前のものが既にあればそれを使い回す。
 * 無ければ新しく作る。カードの中の参照も全て貼り替える。
 */
object IdRemapper {

    fun remapCard(card: CardDef, mapping: Map<String, String>): CardDef = card.copy(
        attributeId = card.attributeId?.let { mapping[it] ?: it },
        raceId = card.raceId?.let { mapping[it] ?: it },
        categoryIds = card.categoryIds.map { mapping[it] ?: it },
        effect = card.effect?.let { remapEffect(it, mapping) },
        continuous = card.continuous.map { remapContinuous(it, mapping) }
    )

    private fun remapEffect(effect: EffectText, m: Map<String, String>): EffectText = effect.copy(
        conditions = effect.conditions.map { remapCondition(it, m) },
        costs = effect.costs.map { remapCost(it, m) },
        limits = effect.limits.map { remapLimit(it, m) },
        summonLocks = effect.summonLocks.map { remapLock(it, m) },
        clauses = effect.clauses.map { clause ->
            clause.copy(
                conditions = clause.conditions.map { remapCondition(it, m) },
                costs = clause.costs.map { remapCost(it, m) },
                limits = clause.limits.map { remapLimit(it, m) },
                summonLocks = clause.summonLocks.map { remapLock(it, m) },
                actions = clause.actions.map { remapAction(it, m) },
                branches = clause.branches.map { branch ->
                    branch.copy(
                        conditions = branch.conditions.map { remapCondition(it, m) },
                        actions = branch.actions.map { remapAction(it, m) }
                    )
                }
            )
        }
    )

    private fun remapLimit(limit: UsageLimit, m: Map<String, String>): UsageLimit =
        limit.copy(categoryId = limit.categoryId?.let { m[it] ?: it })

    private fun remapLock(lock: SummonLock, m: Map<String, String>): SummonLock =
        lock.copy(filters = lock.filters.map { remapFilter(it, m) })

    private fun remapScope(scope: CardScope, m: Map<String, String>): CardScope =
        scope.copy(
            filters = scope.filters.map { remapFilter(it, m) },
            countSpec = scope.countSpec?.let { remapValue(it, m) }
        )

    /** 「〜の数だけ」のような数の指定も、中の対象指定を貼り替える。 */
    private fun remapValue(spec: ValueSpec, m: Map<String, String>): ValueSpec = when (spec) {
        is CountValue -> spec.copy(scope = remapScope(spec.scope, m))
        is FixedValue -> spec
    }

    private fun remapFilter(filter: CardFilter, m: Map<String, String>): CardFilter =
        when (filter) {
            is AttributeFilter -> filter.copy(attributeId = m[filter.attributeId] ?: filter.attributeId)
            is RaceFilter -> filter.copy(raceId = m[filter.raceId] ?: filter.raceId)
            is CategoryFilter -> filter.copy(categoryId = m[filter.categoryId] ?: filter.categoryId)
            else -> filter
        }

    private fun remapCondition(condition: Condition, m: Map<String, String>): Condition =
        when (condition) {
            is CardExistsCondition -> condition.copy(scope = remapScope(condition.scope, m))
            is EventCondition -> condition.copy(filters = condition.filters.map { remapFilter(it, m) })
            else -> condition
        }

    private fun remapCost(cost: Cost, m: Map<String, String>): Cost = when (cost) {
        is DiscardCost -> cost.copy(filters = cost.filters.map { remapFilter(it, m) })
        is TributeCost -> cost.copy(filters = cost.filters.map { remapFilter(it, m) })
        is BanishFromGraveCost -> cost.copy(filters = cost.filters.map { remapFilter(it, m) })
        is MoveCost -> cost.copy(scope = remapScope(cost.scope, m))
        is RevealCost -> cost.copy(scope = remapScope(cost.scope, m))
        else -> cost
    }

    private fun remapAction(action: Action, m: Map<String, String>): Action = when (action) {
        is DestroyAction -> action.copy(scope = remapScope(action.scope, m))
        is BanishAction -> action.copy(scope = remapScope(action.scope, m))
        is ToHandAction -> action.copy(scope = remapScope(action.scope, m))
        is ToGraveAction -> action.copy(scope = remapScope(action.scope, m))
        is ToDeckAction -> action.copy(scope = remapScope(action.scope, m))
        is SpecialSummonAction -> action.copy(scope = remapScope(action.scope, m))
        is ChangePositionAction -> action.copy(scope = remapScope(action.scope, m))
        is SetSpellTrapAction -> action.copy(scope = remapScope(action.scope, m))
        is PlaceSpellTrapAction -> action.copy(scope = remapScope(action.scope, m))
        is ActivateCardAction -> action.copy(scope = remapScope(action.scope, m))
        is GrantProtectionAction -> action.copy(scope = remapScope(action.scope, m))
        is PreventAttackAction -> action.copy(scope = remapScope(action.scope, m))
        is RevealAction -> action.copy(scope = remapScope(action.scope, m))

        is GrantEffectAction -> action.copy(
            scope = remapScope(action.scope, m),
            granted = action.granted.copy(
                conditions = action.granted.conditions.map { remapCondition(it, m) },
                costs = action.granted.costs.map { remapCost(it, m) },
                actions = action.granted.actions.map { remapAction(it, m) }
            )
        )

        is RestrictSummonAction ->
            action.copy(filters = action.filters.map { remapFilter(it, m) })

        is ModifyStatAction -> action.copy(
            scope = remapScope(action.scope, m),
            deltaValue = action.deltaValue?.let { remapValue(it, m) }
        )

        is DamageAction -> action.copy(amountValue = action.amountValue?.let { remapValue(it, m) })
        is RecoverAction -> action.copy(amountValue = action.amountValue?.let { remapValue(it, m) })
        is DrawAction -> action.copy(countValue = action.countValue?.let { remapValue(it, m) })
        is MillAction -> action.copy(countValue = action.countValue?.let { remapValue(it, m) })
        is DiscardAction -> action.copy(countValue = action.countValue?.let { remapValue(it, m) })
        else -> action
    }

    private fun remapContinuous(
        effect: ContinuousEffect,
        m: Map<String, String>
    ): ContinuousEffect = when (effect) {
        is StatBuffEffect -> effect.copy(scope = effect.scope?.let { remapScope(it, m) })
        is ProtectionEffect -> effect.copy(scope = effect.scope?.let { remapScope(it, m) })
        is CannotAttackEffect -> effect.copy(scope = effect.scope?.let { remapScope(it, m) })
    }

    /** カードが参照している ID を全て集める。書き出す範囲を決めるのに使う。 */
    fun referencedIds(card: CardDef): Set<String> {
        val ids = mutableSetOf<String>()
        card.attributeId?.let(ids::add)
        card.raceId?.let(ids::add)
        ids += card.categoryIds

        card.effect?.let { effect ->
            effect.conditions.forEach { collectCondition(it, ids) }
            effect.costs.forEach { collectCost(it, ids) }
            effect.limits.forEach { limit -> limit.categoryId?.let(ids::add) }
            effect.summonLocks.forEach { collectFilters(it.filters, ids) }
            effect.clauses.forEach { clause ->
                clause.conditions.forEach { collectCondition(it, ids) }
                clause.costs.forEach { collectCost(it, ids) }
                clause.limits.forEach { limit -> limit.categoryId?.let(ids::add) }
                clause.summonLocks.forEach { collectFilters(it.filters, ids) }
                clause.actions.forEach { collectAction(it, ids) }
                clause.branches.forEach { branch ->
                    branch.conditions.forEach { collectCondition(it, ids) }
                    branch.actions.forEach { collectAction(it, ids) }
                }
            }
        }
        card.continuous.forEach { collectScope(it.scope, ids) }
        return ids
    }

    private fun collectFilters(filters: List<CardFilter>, ids: MutableSet<String>) {
        filters.forEach { filter ->
            when (filter) {
                is AttributeFilter -> ids += filter.attributeId
                is RaceFilter -> ids += filter.raceId
                is CategoryFilter -> ids += filter.categoryId
                else -> Unit
            }
        }
    }

    private fun collectScope(scope: CardScope?, ids: MutableSet<String>) {
        if (scope == null) return
        collectFilters(scope.filters, ids)
        collectValue(scope.countSpec, ids)
    }

    private fun collectValue(spec: ValueSpec?, ids: MutableSet<String>) {
        if (spec is CountValue) collectScope(spec.scope, ids)
    }

    private fun collectCondition(condition: Condition, ids: MutableSet<String>) {
        when (condition) {
            is CardExistsCondition -> collectScope(condition.scope, ids)
            is EventCondition -> collectFilters(condition.filters, ids)
            else -> Unit
        }
    }

    private fun collectCost(cost: Cost, ids: MutableSet<String>) {
        when (cost) {
            is DiscardCost -> collectFilters(cost.filters, ids)
            is TributeCost -> collectFilters(cost.filters, ids)
            is BanishFromGraveCost -> collectFilters(cost.filters, ids)
            is MoveCost -> collectScope(cost.scope, ids)
            is RevealCost -> collectScope(cost.scope, ids)
            else -> Unit
        }
    }

    private fun collectAction(action: Action, ids: MutableSet<String>) {
        when (action) {
            is DestroyAction -> collectScope(action.scope, ids)
            is BanishAction -> collectScope(action.scope, ids)
            is ToHandAction -> collectScope(action.scope, ids)
            is ToGraveAction -> collectScope(action.scope, ids)
            is ToDeckAction -> collectScope(action.scope, ids)
            is SpecialSummonAction -> collectScope(action.scope, ids)
            is ChangePositionAction -> collectScope(action.scope, ids)
            is SetSpellTrapAction -> collectScope(action.scope, ids)
            is PlaceSpellTrapAction -> collectScope(action.scope, ids)
            is ActivateCardAction -> collectScope(action.scope, ids)
            is GrantProtectionAction -> collectScope(action.scope, ids)
            is PreventAttackAction -> collectScope(action.scope, ids)
            is RevealAction -> collectScope(action.scope, ids)
            is RestrictSummonAction -> collectFilters(action.filters, ids)

            is GrantEffectAction -> {
                collectScope(action.scope, ids)
                action.granted.conditions.forEach { collectCondition(it, ids) }
                action.granted.costs.forEach { collectCost(it, ids) }
                action.granted.actions.forEach { collectAction(it, ids) }
            }

            is ModifyStatAction -> {
                collectScope(action.scope, ids)
                collectValue(action.deltaValue, ids)
            }

            is DamageAction -> collectValue(action.amountValue, ids)
            is RecoverAction -> collectValue(action.amountValue, ids)
            is DrawAction -> collectValue(action.countValue, ids)
            is MillAction -> collectValue(action.countValue, ids)
            is DiscardAction -> collectValue(action.countValue, ids)
            else -> Unit
        }
    }
}
