package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Auto : Interaction {
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

class ContinuousEffectTest {

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Auto())
    }

    private fun monster(
        name: String,
        atk: Int = 1000,
        def: Int = 1000,
        continuous: List<ContinuousEffect> = emptyList()
    ) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = atk, def = def, continuous = continuous
        )
    )

    @Test
    fun `a stat buff applies while the source is face-up on the field`() {
        val (state, engine) = game()
        val me = state.players[0]

        val lord = monster(
            "指揮官", atk = 1000,
            continuous = listOf(
                StatBuffEffect(
                    scope = CardScope(
                        who = PlayerRef.SELF,
                        zone = ZoneType.MONSTER_ZONE,
                        selection = SelectionMode.ALL
                    ),
                    stat = StatKind.ATK,
                    amount = 500
                )
            )
        )
        val ally = monster("味方", atk = 1200)
        me.monsterZones[0] = lord
        me.monsterZones[1] = ally

        assertEquals(1700, engine.atkOf(ally))
        // 適用範囲に自分も入るので、指揮官自身も上がる。
        assertEquals(1500, engine.atkOf(lord))

        // 場を離れれば元に戻る。
        me.monsterZones[0] = null
        assertEquals(1200, engine.atkOf(ally))
    }

    @Test
    fun `a face-down source grants nothing`() {
        val (state, engine) = game()
        val me = state.players[0]
        val lord = monster(
            "伏せた指揮官",
            continuous = listOf(StatBuffEffect(scope = null, stat = StatKind.ATK, amount = 500))
        )
        lord.faceDown = true
        me.monsterZones[0] = lord
        assertEquals(1000, engine.atkOf(lord))

        lord.faceDown = false
        assertEquals(1500, engine.atkOf(lord))
    }

    @Test
    fun `a card immune to opponent effects cannot be chosen by them`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val warded = monster(
            "守られた者",
            continuous = listOf(ProtectionEffect(scope = null, kind = ProtectionKind.OPPONENT_EFFECTS))
        )
        val plain = monster("普通の者")
        me.monsterZones[0] = warded
        me.monsterZones[1] = plain

        val scope = CardScope(
            who = PlayerRef.OPPONENT,
            zone = ZoneType.MONSTER_ZONE,
            selection = SelectionMode.ALL
        )
        // 相手から見ると、耐性持ちは対象に含まれない。
        val fromOpponent = engine.candidates(scope, opponent)
        assertFalse(fromOpponent.any { it === warded })
        assertTrue(fromOpponent.any { it === plain })

        // 自分の効果では選べる。
        val ownScope = scope.copy(who = PlayerRef.SELF)
        assertTrue(engine.candidates(ownScope, me).any { it === warded })
    }

    @Test
    fun `battle destruction protection keeps the monster on the field`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        state.phase = Phase.BATTLE

        val attacker = monster("攻撃側", atk = 2500)
        me.monsterZones[0] = attacker
        val tough = monster(
            "不滅の壁", atk = 1000,
            continuous = listOf(
                ProtectionEffect(scope = null, kind = ProtectionKind.BATTLE_DESTRUCTION)
            )
        )
        opponent.monsterZones[0] = tough

        engine.declareAttack(attacker, tough)
        // ダメージは通るが、破壊はされない。
        assertEquals(8000 - 1500, opponent.life)
        assertTrue(opponent.monsters.any { it === tough })
    }

    @Test
    fun `a locked monster cannot declare an attack`() {
        val (state, engine) = game()
        val me = state.players[0]
        state.phase = Phase.BATTLE

        val locked = monster(
            "縛られた者",
            continuous = listOf(CannotAttackEffect(scope = null))
        )
        me.monsterZones[0] = locked
        assertFalse(engine.canAttack(locked))

        val free = monster("自由な者")
        me.monsterZones[1] = free
        assertTrue(engine.canAttack(free))
    }

    @Test
    fun `continuous effects are written above the activated ones`() {
        val master = MasterData()
        val card = CardDef(
            id = "x", name = "永続持ち", kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            continuous = listOf(
                ProtectionEffect(scope = null, kind = ProtectionKind.OPPONENT_EFFECTS)
            ),
            effect = EffectText(
                clauses = listOf(
                    EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))
                )
            )
        )
        val lines = EffectTextRenderer.render(card, master).lines()
        assertEquals("【永続効果】このカードは相手の効果を受けない。", lines[0])
        assertTrue(lines[1].startsWith("①："))
    }

    @Test
    fun `a card with only a continuous effect still renders text`() {
        val master = MasterData()
        val card = CardDef(
            id = "x", name = "永続だけ", kind = CardKind.MONSTER,
            continuous = listOf(CannotAttackEffect(scope = null))
        )
        assertEquals("【永続効果】このカードは攻撃できない。", EffectTextRenderer.render(card, master))
        assertTrue(card.hasAnyEffect)
        assertFalse(card.hasEffect)
    }
}
