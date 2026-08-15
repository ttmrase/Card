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
        // 【発動後】が既定（魔法は墓地へ送る）のときは書かない。
        assertTrue(lines.none { it.startsWith("【発動後】") })
        assertTrue(lines[2].startsWith("①："))
        // 番号の直後に書いたコストは、その効果にだけ掛かる。
        assertTrue(lines[3].startsWith("②：【コスト】手札を1枚捨てる"))
    }

    @Test
    fun `after activation and limits appear in the card text`() {
        val card = CardDef(
            id = "x", name = "永続テスト", kind = CardKind.SPELL,
            effect = EffectText(
                afterActivation = AfterActivation.STAY_ON_FIELD,
                limits = listOf(UsageLimit(LimitScope.THIS_CARD, 1)),
                clauses = listOf(
                    EffectClause(
                        actions = listOf(RecoverAction(PlayerRef.SELF, 500)),
                        limits = listOf(UsageLimit(LimitScope.SAME_NAME, 2))
                    )
                )
            )
        )
        val text = EffectTextRenderer.render(card, master)
        assertTrue(text.contains("【制限】このカードは1ターンに1度しか発動できない"))
        assertTrue(text.contains("【発動後】そのまま残す"))
        assertTrue(text.contains("同名カードを含めて1ターンに2度まで発動できる"))
    }

    @Test
    fun `a monster effect uses the same bracket format as spells`() {
        val card = CardDef(
            id = "x", name = "誘発モンスター", kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(GameEventType.DESTROYED, selfOnly = true)
                        ),
                        mode = ActivationMode.MANDATORY,
                        actions = listOf(DamageAction(PlayerRef.OPPONENT, 800))
                    )
                )
            )
        )
        val text = EffectTextRenderer.render(card, master)
        assertEquals(
            "①：【条件】このカードが破壊された場合 相手に800ポイントのダメージを与える。",
            text
        )
        // モンスターの既定の【発動後】は「そのまま残す」なので書かれない。
        assertTrue(!text.contains("【発動後】"))
    }

    @Test
    fun `optional effects read as can activate and mandatory ones do not`() {
        fun card(mode: ActivationMode) = CardDef(
            id = "x", name = "テスト", kind = CardKind.MONSTER,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(GameEventType.SUMMONED, selfOnly = true)
                        ),
                        mode = mode,
                        actions = listOf(DrawAction(PlayerRef.SELF, 1))
                    )
                )
            )
        )
        assertTrue(
            EffectTextRenderer.render(card(ActivationMode.OPTIONAL), master)
                .endsWith("ドローできる。")
        )
        assertTrue(
            EffectTextRenderer.render(card(ActivationMode.MANDATORY), master)
                .endsWith("ドローする。")
        )
    }

    @Test
    fun `event conditions name the owner and the card`() {
        val condition = EventCondition(
            event = GameEventType.SPECIAL_SUMMONED,
            who = PlayerRef.OPPONENT,
            filters = listOf(AttributeFilter("attr-dark"), KindFilter(CardKind.MONSTER))
        )
        assertEquals(
            "相手の闇属性モンスターが特殊召喚された場合",
            EffectTextRenderer.conditionToText(condition, master)
        )
    }

    @Test
    fun `a self cost reads as sending this card away`() {
        assertEquals(
            "このカードを墓地へ送る",
            EffectTextRenderer.costToText(DiscardSelfCost(), master)
        )
        assertEquals(
            "このカードを除外する",
            EffectTextRenderer.costToText(DiscardSelfCost(banish = true), master)
        )
    }

    @Test
    fun `category limits name the category`() {
        val limit = UsageLimit(LimitScope.CATEGORY, 1, "cat-araragi")
        assertEquals(
            "「アララギ」カードを含めて1ターンに1度しか発動できない",
            EffectTextRenderer.limitToText(limit, master, cardWide = true)
        )
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
