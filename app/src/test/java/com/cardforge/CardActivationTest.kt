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

private class AutoPick : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> =
        if (min == 0) emptyList() else candidates.take(maxOf(min, 1).coerceAtMost(max))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

class CardActivationTest {

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, AutoPick())
    }

    private fun continuousSpell(name: String, withOnActivation: Boolean) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.SPELL,
            effect = EffectText(
                afterActivation = AfterActivation.STAY_ON_FIELD,
                clauses = buildList {
                    if (withOnActivation) {
                        add(
                            EffectClause(
                                mode = ActivationMode.ON_ACTIVATION,
                                actions = listOf(RecoverAction(PlayerRef.SELF, 400))
                            )
                        )
                    }
                    add(
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                ModifyStatAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        selection = SelectionMode.ALL
                                    ),
                                    StatKind.ATK,
                                    300
                                )
                            )
                        )
                    )
                }
            )
        )
    )

    // --- カードそのものの発動 --------------------------------------------

    @Test
    fun `a spell with only a continuous effect can still be played`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val spell = continuousSpell("永続魔法", withOnActivation = false)
        me.hand.add(spell)

        val ally = CardInstance(
            newId(),
            CardDef(id = newId(), name = "味方", kind = CardKind.MONSTER, atk = 1000, def = 1000)
        )
        me.monsterZones[0] = ally
        assertEquals(1000, engine.atkOf(ally))

        assertTrue("カードの発動ができる", engine.canActivateCardItself(spell, me))
        assertTrue(engine.activatableCards(me).any { it === spell })

        engine.activateCard(spell, me)
        assertTrue("発動後もフィールドに残る", me.spellsAndTraps.any { it === spell })
        assertEquals("永続の効果が働く", 1300, engine.atkOf(ally))
    }

    @Test
    fun `on-activation effects resolve with the card activation`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val spell = continuousSpell("発動時つき", withOnActivation = true)
        me.hand.add(spell)

        engine.activateCard(spell, me)
        assertEquals("発動時の効果処理が走る", 8400, me.life)
        assertTrue(me.spellsAndTraps.any { it === spell })
    }

    @Test
    fun `an on-activation effect cannot be activated on its own afterwards`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val spell = continuousSpell("発動時つき", withOnActivation = true)
        me.hand.add(spell)
        engine.activateCard(spell, me)

        val lifeAfter = me.life
        // 場に残っていても、発動時処理をもう一度使うことはできない。
        assertTrue(engine.activatableClauses(spell, me).isEmpty())
        assertFalse(engine.canActivateCardItself(spell, me))
        engine.activateCard(spell, me)
        assertEquals(lifeAfter, me.life)
    }

    @Test
    fun `an on-activation clause reads as happening when the card is activated`() {
        val card = CardDef(
            id = "x", name = "テスト", kind = CardKind.SPELL,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        mode = ActivationMode.ON_ACTIVATION,
                        actions = listOf(DrawAction(PlayerRef.SELF, 1))
                    )
                )
            )
        )
        val text = EffectTextRenderer.render(card, MasterData())
        assertTrue(text.contains("①：このカードの発動時に、自分はカードを1枚ドローする。"))
    }

    // --- 魔法・罠ゾーンに置く／伏せる／発動する ---------------------------

    @Test
    fun `an effect can set a trap straight from the deck`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val trap = CardInstance(
            newId(),
            CardDef(id = newId(), name = "仕込みの罠", kind = CardKind.TRAP)
        )
        me.deck.add(trap)

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "先読み", kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                SetSpellTrapAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(KindFilter(CardKind.TRAP)),
                                        count = 1
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(spell)

        engine.activateCard(spell, me)
        assertTrue("罠がセットされた", me.spellsAndTraps.any { it === trap })
        assertTrue("裏側で置かれる", trap.faceDown)
        assertEquals("このターンにセットしたので発動できない", false, engine.canActivateTrap(trap, me))
    }

    @Test
    fun `placing a card face up does not activate it but its continuous effect works`() =
        runBlocking {
            val (state, engine) = game()
            val me = state.players[0]

            val banner = continuousSpell("旗", withOnActivation = true)
            me.deck.add(banner)
            val ally = CardInstance(
                newId(),
                CardDef(id = newId(), name = "味方", kind = CardKind.MONSTER, atk = 1000, def = 1000)
            )
            me.monsterZones[0] = ally

            val placer = CardInstance(
                newId(),
                CardDef(
                    id = newId(), name = "設置", kind = CardKind.SPELL,
                    effect = EffectText(
                        clauses = listOf(
                            EffectClause(
                                actions = listOf(
                                    PlaceSpellTrapAction(
                                        CardScope(
                                            who = PlayerRef.SELF,
                                            zone = ZoneType.DECK,
                                            filters = listOf(KindFilter(CardKind.SPELL)),
                                            count = 1
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            me.hand.add(placer)

            engine.activateCard(placer, me)
            assertTrue("表側で置かれる", me.spellsAndTraps.any { it === banner } && !banner.faceDown)
            assertEquals("発動していないので発動時効果は起きない", 8000, me.life)
            assertEquals("永続の効果は働く", 1300, engine.atkOf(ally))
        }

    // --- 切り抜きの計算 ---------------------------------------------------

    @Test
    fun `no zoom or pan crops the whole image`() {
        val rect = computeCrop(zoom = 1f, offsetX = 0f, offsetY = 0f, 300f, 400f)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.width, 0.001f)
        assertEquals(1f, rect.height, 0.001f)
    }

    @Test
    fun `zooming in keeps the crop inside the image`() {
        val rect = computeCrop(zoom = 2f, offsetX = 0f, offsetY = 0f, 300f, 400f)
        assertEquals(0.25f, rect.left, 0.001f)
        assertEquals(0.25f, rect.top, 0.001f)
        assertEquals(0.5f, rect.width, 0.001f)
        assertEquals(0.5f, rect.height, 0.001f)
    }

    @Test
    fun `panning far never leaves the image bounds`() {
        val rect = computeCrop(zoom = 2f, offsetX = -10_000f, offsetY = 10_000f, 300f, 400f)
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.left + rect.width <= 1.001f)
        assertTrue(rect.top + rect.height <= 1.001f)
    }
}
