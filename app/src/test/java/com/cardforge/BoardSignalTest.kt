package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class SignalPicker : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 画面の演出に渡す合図が、正しいカード・量・相手を持っていることを確かめる。 */
class BoardSignalTest {

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, SignalPicker())
    }

    private fun monster(name: String, atk: Int = 1000) = CardInstance(
        newId(),
        CardDef(id = newId(), name = name, kind = CardKind.MONSTER, level = 4, atk = atk, def = 1000)
    )

    private fun spell(clause: EffectClause) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "呪文", kind = CardKind.SPELL,
            effect = EffectText(locations = listOf(ActivationLocation.HAND), clauses = listOf(clause))
        )
    )

    @Test
    fun `an attack names its target and the damage carries its amount`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        state.phase = Phase.BATTLE
        val attacker = monster("攻め手", atk = 1800).also { it.summonedOnTurn = 1 }
        val defender = monster("守り手", atk = 1000)
        me.monsterZones[0] = attacker
        opponent.monsterZones[0] = defender

        engine.declareAttack(attacker, defender)

        val attack = state.signals.first { it.kind == BoardSignalKind.ATTACK }
        assertSame(attacker, attack.card)
        assertSame(defender, attack.target)

        val damage = state.signals.first { it.kind == BoardSignalKind.DAMAGE }
        assertEquals(1, damage.playerIndex)
        assertEquals(800, damage.amount)
    }

    @Test
    fun `a direct attack has no target`() = runBlocking {
        val (state, engine) = game()
        state.phase = Phase.BATTLE
        val attacker = monster("攻め手").also { it.summonedOnTurn = 1 }
        state.players[0].monsterZones[0] = attacker

        engine.declareAttack(attacker, null)

        assertNull(state.signals.first { it.kind == BoardSignalKind.ATTACK }.target)
    }

    @Test
    fun `the finishing blow is still shown`() = runBlocking {
        val (state, engine) = game()
        val opponent = state.players[1]
        opponent.life = 500

        engine.dealDamage(opponent, 1200)

        assertTrue(state.finished)
        val damage = state.signals.single { it.kind == BoardSignalKind.DAMAGE }
        assertEquals(1200, damage.amount)
        assertEquals(1, damage.playerIndex)
    }

    @Test
    fun `a destroyed card is shown leaving once`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val victim = monster("標的")
        state.players[1].monsterZones[0] = victim
        val card = spell(EffectClause(actions = listOf(DestroyAction(CardScope()))))
        me.hand.add(card)

        engine.activateCard(card, me)

        val leaving = state.signals.filter { it.kind.isLeaving && it.card === victim }
        assertEquals(listOf(BoardSignalKind.DESTROYED), leaving.map { it.kind })
    }

    @Test
    fun `returning a card to the deck leaves the field`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val victim = monster("標的")
        state.players[1].monsterZones[0] = victim
        val card = spell(EffectClause(actions = listOf(ToDeckAction(CardScope()))))
        me.hand.add(card)

        engine.activateCard(card, me)

        assertTrue(
            "「フィールドを離れた」が起きる",
            state.eventsThisTurn.any { it.type == GameEventType.LEFT_FIELD && it.card === victim }
        )
        val returned = state.signals.single { it.card === victim && it.kind.isLeaving }
        assertEquals(BoardSignalKind.RETURNED, returned.kind)
        assertTrue(returned.text.contains("デッキへ"))
    }
}
