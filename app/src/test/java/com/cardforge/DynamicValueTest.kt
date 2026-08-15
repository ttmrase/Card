package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.computeCrop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Yes(private val takeOptional: Boolean = false) : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> =
        if (min == 0 && !takeOptional) emptyList()
        else candidates.take(maxOf(min, 1).coerceAtMost(max))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

class DynamicValueTest {

    private fun game(takeOptional: Boolean = false): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Yes(takeOptional))
    }

    private fun monster(name: String) = CardInstance(
        newId(),
        CardDef(id = newId(), name = name, kind = CardKind.MONSTER, atk = 1000, def = 1000)
    )

    private val graveyardCount = CountValue(
        scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.GRAVEYARD,
            filters = listOf(KindFilter(CardKind.MONSTER)),
            selection = SelectionMode.ALL
        ),
        multiplier = 100
    )

    // --- 枚数で決まる数値 -------------------------------------------------

    @Test
    fun `a value can be counted from the board`() {
        val (state, engine) = game()
        val me = state.players[0]
        assertEquals(0, engine.resolveValue(graveyardCount, me))

        repeat(3) { me.graveyard.add(monster("墓地$it")) }
        assertEquals(300, engine.resolveValue(graveyardCount, me))

        assertEquals(750, engine.resolveValue(FixedValue(750), me))
    }

    @Test
    fun `a stat change can scale with the count`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        repeat(4) { me.graveyard.add(monster("墓地$it")) }

        val target = monster("強化される者")
        me.monsterZones[0] = target

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "墓の力", kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                ModifyStatAction(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        selection = SelectionMode.ALL
                                    ),
                                    stat = StatKind.ATK,
                                    deltaValue = graveyardCount
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(spell)

        engine.activateCard(spell, me)
        // 墓地のモンスター4体 × 100。魔法自身は墓地に行くがモンスターではない。
        assertEquals(1400, engine.atkOf(target))
    }

    @Test
    fun `a counted value reads as a multiplication`() {
        val master = MasterData()
        assertEquals(
            "自分の墓地のモンスターの数×100",
            EffectTextRenderer.valueToText(graveyardCount, master)
        )
        assertEquals(
            "自分の墓地のモンスターの数×200＋500",
            EffectTextRenderer.valueToText(
                graveyardCount.copy(multiplier = 200, base = 500), master
            )
        )
    }

    @Test
    fun `an old fixed value still works`() {
        val action = ModifyStatAction(CardScope(), StatKind.ATK, delta = 800)
        assertEquals(FixedValue(800), action.amountSpec)
    }

    // --- フェイズの条件 ---------------------------------------------------

    @Test
    fun `a phase condition limits when the card can be activated`() {
        val (state, engine) = game()
        val me = state.players[0]
        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "終わりの合図", kind = CardKind.SPELL,
                effect = EffectText(
                    conditions = listOf(PhaseCondition(listOf(Phase.END))),
                    clauses = listOf(
                        EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))
                    )
                )
            )
        )
        me.hand.add(spell)
        me.deck.add(monster("山札"))

        state.phase = Phase.MAIN1
        assertFalse("メインでは発動できない", engine.activatableCards(me).any { it === spell })

        state.phase = Phase.END
        assertTrue("エンドフェイズなら発動できる", engine.activatableCards(me).any { it === spell })
    }

    @Test
    fun `a card without a phase condition stays limited to the main phase`() {
        val (state, engine) = game()
        val me = state.players[0]
        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "普通の魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100)))
                    )
                )
            )
        )
        me.hand.add(spell)

        state.phase = Phase.MAIN1
        assertTrue(engine.activatableCards(me).any { it === spell })
        state.phase = Phase.BATTLE
        assertFalse(engine.activatableCards(me).any { it === spell })
    }

    @Test
    fun `the end phase offers the cards that name it`() = runBlocking {
        val (state, engine) = game(takeOptional = true)
        val me = state.players[0]
        me.deck.add(monster("山札"))

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "終わりの合図", kind = CardKind.SPELL,
                effect = EffectText(
                    conditions = listOf(PhaseCondition(listOf(Phase.END))),
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 700)))
                    )
                )
            )
        )
        me.hand.add(spell)

        // メイン2からエンドフェイズへ進むと、確認が出て発動できる。
        state.phase = Phase.MAIN2
        engine.advancePhase()
        assertEquals(Phase.END, state.phase)
        assertEquals("エンドフェイズに発動された", 8700, me.life)
    }

    @Test
    fun `a phase condition reads plainly`() {
        assertEquals(
            "エンドフェイズである",
            EffectTextRenderer.conditionToText(PhaseCondition(listOf(Phase.END)), MasterData())
        )
    }

    // --- テキストの手直し -------------------------------------------------

    @Test
    fun `a hand written text replaces the generated one`() {
        val card = CardDef(
            id = "x", name = "テスト", kind = CardKind.SPELL,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))
                )
            )
        )
        val master = MasterData()
        assertTrue(EffectTextRenderer.render(card, master).contains("①："))

        val edited = card.copy(textOverride = "①：自分はデッキから1枚ドローする。")
        assertEquals("①：自分はデッキから1枚ドローする。", EffectTextRenderer.render(edited, master))
        // 手直しをやめれば元に戻る。
        assertEquals(
            EffectTextRenderer.renderGenerated(card, master),
            EffectTextRenderer.render(edited.copy(textOverride = null), master)
        )
    }

    @Test
    fun `a blank override falls back to the generated text`() {
        val card = CardDef(
            id = "x", name = "テスト", kind = CardKind.SPELL,
            textOverride = "   ",
            effect = EffectText(
                clauses = listOf(EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1))))
            )
        )
        assertTrue(EffectTextRenderer.render(card, MasterData()).contains("①："))
    }

    // --- 切り抜きの計算 ---------------------------------------------------

    @Test
    fun `a fitted image crops to the frame it fills`() {
        // 300x400 の画像を 300x400 の表示領域へ。枠も 300x400 なので全体が入る。
        val rect = computeCrop(
            imageWidth = 300, imageHeight = 400,
            containerWidth = 300f, containerHeight = 400f,
            frameWidth = 300f, frameHeight = 400f,
            zoom = 1f, offsetX = 0f, offsetY = 0f
        )
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.width, 0.001f)
        assertEquals(1f, rect.height, 0.001f)
    }

    @Test
    fun `zooming in takes the middle of the image`() {
        val rect = computeCrop(
            imageWidth = 300, imageHeight = 400,
            containerWidth = 300f, containerHeight = 400f,
            frameWidth = 300f, frameHeight = 400f,
            zoom = 2f, offsetX = 0f, offsetY = 0f
        )
        assertEquals(0.25f, rect.left, 0.001f)
        assertEquals(0.25f, rect.top, 0.001f)
        assertEquals(0.5f, rect.width, 0.001f)
        assertEquals(0.5f, rect.height, 0.001f)
    }

    @Test
    fun `a wide image never crops outside the picture`() {
        // 横長の画像を縦長の枠に入れると、等倍では枠のほうが縦に大きい。
        // はみ出した分は切り詰められ、画像の外は選ばれない。
        val rect = computeCrop(
            imageWidth = 1600, imageHeight = 900,
            containerWidth = 600f, containerHeight = 800f,
            frameWidth = 600f, frameHeight = 800f,
            zoom = 1f, offsetX = 0f, offsetY = 0f
        )
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.left + rect.width <= 1.001f)
        assertTrue(rect.top + rect.height <= 1.001f)

        // 拡大すれば枠を埋められ、使われる範囲は画像の一部になる。
        val zoomed = computeCrop(
            imageWidth = 1600, imageHeight = 900,
            containerWidth = 600f, containerHeight = 800f,
            frameWidth = 600f, frameHeight = 800f,
            zoom = 3f, offsetX = 0f, offsetY = 0f
        )
        assertTrue("拡大すると一部だけを使う", zoomed.width < 1f)
        assertTrue(zoomed.left + zoomed.width <= 1.001f)
    }

    @Test
    fun `panning far never leaves the image`() {
        val rect = computeCrop(
            imageWidth = 300, imageHeight = 400,
            containerWidth = 300f, containerHeight = 400f,
            frameWidth = 300f, frameHeight = 400f,
            zoom = 3f, offsetX = -9999f, offsetY = 9999f
        )
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.left + rect.width <= 1.001f)
        assertTrue(rect.top + rect.height <= 1.001f)
    }
}
