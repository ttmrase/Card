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
        clauses = effect.clauses.map { clause ->
            clause.copy(
                conditions = clause.conditions.map { remapCondition(it, m) },
                costs = clause.costs.map { remapCost(it, m) },
                limits = clause.limits.map { remapLimit(it, m) },
                actions = clause.actions.map { remapAction(it, m) }
            )
        }
    )

    private fun remapLimit(limit: UsageLimit, m: Map<String, String>): UsageLimit =
        limit.copy(categoryId = limit.categoryId?.let { m[it] ?: it })

    private fun remapScope(scope: CardScope, m: Map<String, String>): CardScope =
        scope.copy(filters = scope.filters.map { remapFilter(it, m) })

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
        else -> cost
    }

    private fun remapAction(action: Action, m: Map<String, String>): Action = when (action) {
        is DestroyAction -> action.copy(scope = remapScope(action.scope, m))
        is BanishAction -> action.copy(scope = remapScope(action.scope, m))
        is ToHandAction -> action.copy(scope = remapScope(action.scope, m))
        is ToGraveAction -> action.copy(scope = remapScope(action.scope, m))
        is ToDeckAction -> action.copy(scope = remapScope(action.scope, m))
        is SpecialSummonAction -> action.copy(scope = remapScope(action.scope, m))
        is ModifyStatAction -> action.copy(scope = remapScope(action.scope, m))
        is ChangePositionAction -> action.copy(scope = remapScope(action.scope, m))
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

        fun collectFilters(filters: List<CardFilter>) {
            filters.forEach { filter ->
                when (filter) {
                    is AttributeFilter -> ids += filter.attributeId
                    is RaceFilter -> ids += filter.raceId
                    is CategoryFilter -> ids += filter.categoryId
                    else -> Unit
                }
            }
        }

        fun collectScope(scope: CardScope?) = scope?.let { collectFilters(it.filters) }

        fun collectCondition(condition: Condition) = when (condition) {
            is CardExistsCondition -> collectScope(condition.scope)
            is EventCondition -> collectFilters(condition.filters)
            else -> Unit
        }

        fun collectCost(cost: Cost) = when (cost) {
            is DiscardCost -> collectFilters(cost.filters)
            is TributeCost -> collectFilters(cost.filters)
            is BanishFromGraveCost -> collectFilters(cost.filters)
            else -> Unit
        }

        fun collectAction(action: Action) = when (action) {
            is DestroyAction -> collectScope(action.scope)
            is BanishAction -> collectScope(action.scope)
            is ToHandAction -> collectScope(action.scope)
            is ToGraveAction -> collectScope(action.scope)
            is ToDeckAction -> collectScope(action.scope)
            is SpecialSummonAction -> collectScope(action.scope)
            is ModifyStatAction -> collectScope(action.scope)
            is ChangePositionAction -> collectScope(action.scope)
            else -> Unit
        }

        card.effect?.let { effect ->
            effect.conditions.forEach(::collectCondition)
            effect.costs.forEach(::collectCost)
            effect.limits.forEach { limit -> limit.categoryId?.let(ids::add) }
            effect.clauses.forEach { clause ->
                clause.conditions.forEach(::collectCondition)
                clause.costs.forEach(::collectCost)
                clause.limits.forEach { limit -> limit.categoryId?.let(ids::add) }
                clause.actions.forEach(::collectAction)
            }
        }
        card.continuous.forEach { collectScope(it.scope) }
        return ids
    }
}
