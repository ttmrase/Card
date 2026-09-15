package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RevealPicker : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 「直前の効果で扱ったカード」を、別のカードから指せることを確かめる。 */
class LastHandledTest {

    private val master = MasterData()

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, RevealPicker())
    }

    private fun monster(name: String) = CardInstance(
        newId(),
        CardDef(id = newId(), name = name, kind = CardKind.MONSTER, level = 4, atk = 1000, def = 1000)
    )

    private fun spell(name: String, clause: EffectClause) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(clause)
            )
        )
    )

    /** 相手の手札を見せる効果。 */
    private fun peekAtHand(name: String) = spell(
        name,
        EffectClause(
            actions = listOf(
                RevealAction(
                    CardScope(
                        who = PlayerRef.OPPONENT,
                        zone = ZoneType.HAND,
                        selection = SelectionMode.ALL
                    )
                )
            )
        )
    )

    @Test
    fun `a later card can destroy what the previous effect revealed`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val seen = monster("見られた者")
        opponent.hand.add(seen)

        val peek = peekAtHand("覗き見")
        // 「カードを確認したターン、直前の効果で扱ったカードを破壊する」。
        val strike = spell(
            "追い打ち",
            EffectClause(
                conditions = listOf(
                    EventCondition(
                        event = GameEventType.REVEALED,
                        who = PlayerRef.BOTH,
                        window = EventWindow.THIS_TURN
                    )
                ),
                actions = listOf(
                    DestroyAction(CardScope(triggerCard = TriggerCardRef.LAST_HANDLED))
                )
            )
        )
        me.hand.add(peek)
        me.hand.add(strike)

        // 確認していないうちは発動できない。
        assertTrue(
            "まだ何も確認していない",
            engine.activatableClauses(strike, me).isEmpty()
        )

        engine.activateCard(peek, me)
        assertEquals(listOf(0), engine.activatableClauses(strike, me))

        engine.activateCard(strike, me)
        assertTrue("見られたカードが破壊された", opponent.hand.none { it === seen })
        assertTrue(opponent.graveyard.any { it === seen })
    }

    @Test
    fun `the revealed card can also be picked up as the triggering card`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val seen = monster("見られた者")
        opponent.hand.add(seen)

        // 確認されたその瞬間に反応して、そのカードを墓地へ送る罠。
        val trap = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "暴露", kind = CardKind.TRAP,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.REVEALED,
                                    who = PlayerRef.OPPONENT
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(
                                ToGraveAction(CardScope(triggerCard = TriggerCardRef.EVENT_CARD))
                            )
                        )
                    )
                )
            )
        )
        me.spellTrapZones[0] = trap
        trap.faceDown = true
        trap.setOnTurn = 1

        val peek = peekAtHand("覗き見")
        me.hand.add(peek)
        engine.activateCard(peek, me)

        assertTrue("確認された瞬間に墓地へ送られた", opponent.graveyard.any { it === seen })
    }

    @Test
    fun `the memory is forgotten when the turn changes`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        opponent.hand.add(monster("見られた者"))
        me.deck.add(monster("山札の1枚目"))
        opponent.deck.add(monster("相手の山札"))

        val peek = peekAtHand("覗き見")
        me.hand.add(peek)
        engine.activateCard(peek, me)
        val scope = CardScope(triggerCard = TriggerCardRef.LAST_HANDLED)
        assertEquals(1, engine.candidates(scope, me).size)

        engine.endTurnImmediately()
        assertTrue("ターンが変われば忘れる", engine.candidates(scope, me).isEmpty())
    }

    @Test
    fun `the reference reads plainly`() {
        assertEquals(
            "直前の効果で扱ったカード",
            EffectTextRenderer.scopeToText(
                CardScope(triggerCard = TriggerCardRef.LAST_HANDLED), master
            )
        )
        assertEquals(
            "相手のカードが確認・公開された場合",
            EffectTextRenderer.conditionToText(
                EventCondition(event = GameEventType.REVEALED), master
            )
        )
    }
}
