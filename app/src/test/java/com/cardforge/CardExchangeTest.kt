package com.cardforge

import com.cardforge.data.CardExchange
import com.cardforge.data.IdRemapper
import com.cardforge.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 書き出しファイルの往復と、別の端末で取り込んだときの ID 貼り替えを確認する。
 * ファイル入出力は Android の API なので、ここでは中身の組み立てだけを見る。
 */
class CardExchangeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleCard(attributeId: String, categoryId: String) = CardDef(
        id = "card-1",
        name = "テストカード",
        kind = CardKind.MONSTER,
        level = 4,
        attributeId = attributeId,
        atk = 1500,
        def = 1000,
        categoryIds = listOf(categoryId),
        continuous = listOf(
            StatBuffEffect(
                scope = CardScope(
                    who = PlayerRef.SELF,
                    zone = ZoneType.MONSTER_ZONE,
                    filters = listOf(CategoryFilter(categoryId)),
                    selection = SelectionMode.ALL
                ),
                stat = StatKind.ATK,
                amount = 300
            )
        ),
        effect = EffectText(
            limits = listOf(UsageLimit(LimitScope.CATEGORY, 1, categoryId)),
            clauses = listOf(
                EffectClause(
                    conditions = listOf(
                        EventCondition(
                            event = GameEventType.SUMMONED,
                            filters = listOf(AttributeFilter(attributeId))
                        )
                    ),
                    actions = listOf(
                        DestroyAction(
                            CardScope(
                                who = PlayerRef.OPPONENT,
                                zone = ZoneType.MONSTER_ZONE,
                                filters = listOf(CategoryFilter(categoryId))
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `an export survives a json round trip`() {
        val payload = CardExchange(
            master = MasterData(
                attributes = listOf(NamedEntry("attr-1", "闇")),
                categories = listOf(NamedEntry("cat-1", "アララギ"))
            ),
            cards = listOf(sampleCard("attr-1", "cat-1")),
            decks = listOf(Deck("deck-1", "テストデッキ", List(3) { "card-1" })),
            images = mapOf("/data/card.jpg" to "QUJD")
        )
        val restored = json.decodeFromString(
            CardExchange.serializer(),
            json.encodeToString(CardExchange.serializer(), payload)
        )
        assertEquals(payload, restored)
    }

    @Test
    fun `every referenced id is collected so nothing is exported dangling`() {
        val card = sampleCard("attr-1", "cat-1")
        val ids = IdRemapper.referencedIds(card)
        assertTrue("属性", "attr-1" in ids)
        assertTrue("カテゴリ", "cat-1" in ids)
    }

    @Test
    fun `remapping rewrites every reference to the local ids`() {
        val card = sampleCard("attr-remote", "cat-remote")
        val mapping = mapOf("attr-remote" to "attr-local", "cat-remote" to "cat-local")
        val remapped = IdRemapper.remapCard(card, mapping)

        assertEquals("attr-local", remapped.attributeId)
        assertEquals(listOf("cat-local"), remapped.categoryIds)

        // 元の ID がどこにも残っていないこと。
        val remaining = IdRemapper.referencedIds(remapped)
        assertFalse("attr-remote" in remaining)
        assertFalse("cat-remote" in remaining)
        assertTrue("attr-local" in remaining)
        assertTrue("cat-local" in remaining)

        // 効果の中の参照も貼り替わっていること。
        val limit = remapped.effect!!.limits.first()
        assertEquals("cat-local", limit.categoryId)

        val action = remapped.effect!!.clauses.first().actions.first() as DestroyAction
        assertEquals(
            listOf(CategoryFilter("cat-local")),
            action.scope.filters
        )

        val trigger = remapped.effect!!.clauses.first().conditions
            .filterIsInstance<EventCondition>().first()
        assertEquals(listOf(AttributeFilter("attr-local")), trigger.filters)

        val buff = remapped.continuous.first() as StatBuffEffect
        assertEquals(listOf(CategoryFilter("cat-local")), buff.scope!!.filters)
    }

    @Test
    fun `unmapped ids are left alone`() {
        val card = sampleCard("attr-1", "cat-1")
        val remapped = IdRemapper.remapCard(card, emptyMap())
        assertEquals(card, remapped)
    }
}
