package com.cardforge

import com.cardforge.data.DefaultData
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextTest {

    private val master = MasterData(
        attributes = listOf(NamedEntry("attr-dark", "闇")),
        races = listOf(NamedEntry("race-dragon", "ドラゴン族")),
        categories = listOf(NamedEntry("cat-araragi", "アララギ"))
    )

    @Test
    fun `a target phrase reads as owner zone filters and count`() {
        val scope = CardScope(
            who = PlayerRef.OPPONENT,
            zone = ZoneType.FIELD,
            filters = listOf(AttributeFilter("attr-dark"), KindFilter(CardKind.MONSTER)),
            count = 1,
            selection = SelectionMode.CHOOSE
        )
        assertEquals("相手フィールドの闇属性モンスター1体", EffectTextRenderer.scopeToText(scope, master))
        assertEquals(
            "相手フィールドの闇属性モンスター1体を選んで破壊する",
            EffectTextRenderer.actionToText(DestroyAction(scope), master)
        )
    }

    @Test
    fun `selecting every target drops the count`() {
        val scope = CardScope(
            who = PlayerRef.OPPONENT,
            zone = ZoneType.MONSTER_ZONE,
            selection = SelectionMode.ALL
        )
        assertEquals(
            "相手モンスターゾーンの全てのモンスター",
            EffectTextRenderer.scopeToText(scope, master)
        )
    }

    @Test
    fun `categories are referenced by name in effect text`() {
        val scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.DECK,
            filters = listOf(CategoryFilter("cat-araragi"), KindFilter(CardKind.MONSTER)),
            count = 1
        )
        val text = EffectTextRenderer.actionToText(
            SpecialSummonAction(scope, Position.ATTACK, PlayerRef.SELF), master
        )
        assertEquals(
            "自分のデッキの「アララギ」モンスター1体を選んで自分のモンスターゾーンに表側攻撃表示で特殊召喚する",
            text
        )
    }

    @Test
    fun `spell and trap cards default to activating on the field`() {
        val card = CardDef(
            id = "x", name = "テスト", kind = CardKind.SPELL,
            effect = EffectText(
                clauses = listOf(EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1))))
            )
        )
        val text = EffectTextRenderer.render(card, master)
        assertTrue(text.startsWith("【場所】フィールド"))
        assertTrue(text.contains("①：自分はカードを1枚ドローする。"))
    }

    @Test
    fun `card wide clauses render above the numbered effects`() {
        val card = CardDef(
            id = "x", name = "テスト", kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                costs = listOf(PayLifeCost(500)),
                clauses = listOf(
                    EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1))),
                    EffectClause(
                        costs = listOf(DiscardCost(1)),
                        actions = listOf(DamageAction(PlayerRef.OPPONENT, 800))
                    )
                )
            )
        )
        val lines = EffectTextRenderer.render(card, master).lines()
        assertEquals("【場所】手札", lines[0])
        assertEquals("【コスト】ライフを500ポイント払う", lines[1])
        assertTrue(lines[2].startsWith("①："))
        // 番号の直後に書いたコストは、その効果にだけ掛かる。
        assertTrue(lines[3].startsWith("②：【コスト】手札を1枚捨てる"))
    }

    @Test
    fun `a card without effect data renders no text`() {
        val card = CardDef(id = "x", name = "通常", kind = CardKind.MONSTER)
        assertEquals("", EffectTextRenderer.render(card, master))
        assertTrue(!card.hasEffect)
    }

    @Test
    fun `the whole library survives a json round trip`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val seed = DefaultData.seed()
        val restored = json.decodeFromString(
            Library.serializer(),
            json.encodeToString(Library.serializer(), seed)
        )
        assertEquals(seed, restored)
    }
}
