package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** テスト中の選択は常に「先頭から必要数だけ」で自動応答する。 */
private class ScriptedInteraction(private val activateTraps: Boolean = false) : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int,
        prompt: String,
        candidates: List<CardInstance>,
        min: Int,
        max: Int
    ): List<CardInstance> {
        if (min == 0 && !activateTraps) return emptyList()
        return candidates.take(maxOf(min, 1).coerceAtMost(max))
    }

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>): Int? =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String): Boolean = true

    override suspend fun chooseOption(
        playerIndex: Int,
        prompt: String,
        options: List<String>
    ): Int = 0
}

private fun monster(name: String, level: Int, atk: Int, def: Int) = CardDef(
    id = newId(), name = name, kind = CardKind.MONSTER, level = level, atk = atk, def = def
)

private fun inst(card: CardDef) = CardInstance(newId(), card)

private fun freshGame(): Pair<GameState, GameEngine> {
    val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
    state.turnPlayerIndex = 0
    state.turn = 1
    state.phase = Phase.MAIN1
    return state to GameEngine(state, ScriptedInteraction())
}

class GameRulesTest {

    @Test
    fun `both players start with 8000 life`() {
        val (state, _) = freshGame()
        assertTrue(state.players.all { it.life == DeckRules.STARTING_LIFE })
        assertEquals(8000, DeckRules.STARTING_LIFE)
    }

    @Test
    fun `tribute requirements follow the level bands`() {
        assertEquals(0, monster("a", 1, 0, 0).tributesRequired)
        assertEquals(0, monster("a", 4, 0, 0).tributesRequired)
        assertEquals(1, monster("a", 5, 0, 0).tributesRequired)
        assertEquals(1, monster("a", 6, 0, 0).tributesRequired)
        assertEquals(2, monster("a", 7, 0, 0).tributesRequired)
        assertEquals(2, monster("a", 12, 0, 0).tributesRequired)
    }

    @Test
    fun `high level monsters need tributes on the field`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val low = inst(monster("下級", 4, 1800, 1000))
        val high = inst(monster("上級", 7, 2500, 2000))
        player.hand.addAll(listOf(low, high))

        assertFalse(engine.canNormalSummon(high, player))
        assertTrue(engine.canNormalSummon(low, player))

        engine.normalSummon(low, player, asSet = false)
        assertEquals(1, player.monsters.size)
        // 通常召喚は1ターンに1度だけ。
        assertFalse(engine.canNormalSummon(high, player))

        player.normalSummonUsed = false
        player.monsterZones[1] = inst(monster("追加", 4, 1000, 1000))
        assertTrue(engine.canNormalSummon(high, player))

        engine.normalSummon(high, player, asSet = false)
        assertEquals(2, player.graveyard.size)
        assertEquals(1, player.monsters.size)
        assertTrue(player.monsters[0] === high)
    }

    @Test
    fun `direct attacks are blocked while the opponent controls a monster`() = runBlocking {
        val (state, engine) = freshGame()
        state.phase = Phase.BATTLE
        state.turn = 2
        val attackingPlayer = state.players[0]
        val defendingPlayer = state.players[1]

        val attacker = inst(monster("アタッカー", 4, 1800, 1000))
        attackingPlayer.monsterZones[0] = attacker
        defendingPlayer.monsterZones[0] = inst(monster("壁", 4, 1000, 1000))

        assertFalse(engine.canAttackDirectly())
        engine.declareAttack(attacker, null)
        assertEquals(8000, defendingPlayer.life)

        attacker.hasAttacked = false
        defendingPlayer.monsterZones[0] = null
        assertTrue(engine.canAttackDirectly())
        engine.declareAttack(attacker, null)
        assertEquals(8000 - 1800, defendingPlayer.life)
    }

    @Test
    fun `battle damage uses the attack difference`() = runBlocking {
        val (state, engine) = freshGame()
        state.phase = Phase.BATTLE
        state.turn = 2
        val a = state.players[0]
        val b = state.players[1]

        val attacker = inst(monster("攻2000", 4, 2000, 1000))
        val defender = inst(monster("攻1200", 4, 1200, 1000))
        a.monsterZones[0] = attacker
        b.monsterZones[0] = defender

        engine.declareAttack(attacker, defender)
        assertEquals(8000 - 800, b.life)
        assertEquals(1, b.graveyard.size)
        assertEquals(8000, a.life)
    }

    @Test
    fun `attacking into higher defence damages the attacker but destroys nothing`() = runBlocking {
        val (state, engine) = freshGame()
        state.phase = Phase.BATTLE
        state.turn = 2
        val a = state.players[0]
        val b = state.players[1]

        val attacker = inst(monster("攻2000", 4, 2000, 1000))
        a.monsterZones[0] = attacker
        val wall = inst(monster("守2500", 4, 0, 2500))
        wall.position = Position.DEFENSE
        b.monsterZones[0] = wall

        engine.declareAttack(attacker, wall)
        assertEquals(8000 - 500, a.life)
        assertEquals(1, b.monsters.size)
    }

    @Test
    fun `traps cannot be activated on the turn they were set`() {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val trap = inst(
            CardDef(
                id = newId(), name = "テスト罠", kind = CardKind.TRAP,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(actions = listOf(DamageAction(PlayerRef.OPPONENT, 500)))
                    )
                )
            )
        )
        player.hand.add(trap)
        engine.setSpellTrap(trap, player)

        assertTrue(trap.faceDown)
        assertFalse(engine.canActivateTrap(trap, player))

        state.turn = 2
        assertTrue(engine.canActivateTrap(trap, player))

        // 次のターン以降は相手ターンでも発動できる。
        state.turnPlayerIndex = 1
        assertTrue(engine.canActivateTrap(trap, player))
    }

    @Test
    fun `spells can only be activated on their controllers turn in a main phase`() {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "テスト魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1))))
                )
            )
        )
        player.hand.add(spell)
        player.deck.add(inst(monster("山札", 4, 100, 100)))

        assertTrue(engine.activatableCards(player).any { it === spell })

        state.turnPlayerIndex = 1
        assertFalse(engine.activatableCards(player).any { it === spell })

        state.turnPlayerIndex = 0
        state.phase = Phase.BATTLE
        assertFalse(engine.activatableCards(player).any { it === spell })
    }

    @Test
    fun `location condition cost and effect all gate activation`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val enemy = state.players[1]

        val spell = inst(
            CardDef(
                id = newId(), name = "破壊魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    costs = listOf(PayLifeCost(1000)),
                    conditions = listOf(
                        CardExistsCondition(
                            scope = CardScope(
                                who = PlayerRef.OPPONENT,
                                zone = ZoneType.MONSTER_ZONE,
                                filters = listOf(KindFilter(CardKind.MONSTER))
                            ),
                            atLeast = 1
                        )
                    ),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                DestroyAction(
                                    CardScope(
                                        who = PlayerRef.OPPONENT,
                                        zone = ZoneType.MONSTER_ZONE,
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        player.hand.add(spell)

        // 条件（相手モンスターの存在）を満たすまでは発動できない。
        assertFalse(engine.activatableCards(player).any { it === spell })

        enemy.monsterZones[0] = inst(monster("的", 4, 1000, 1000))
        assertTrue(engine.activatableCards(player).any { it === spell })

        engine.activateCard(spell, player)
        assertEquals(7000, player.life)
        assertTrue(enemy.monsters.isEmpty())
        assertEquals(1, enemy.graveyard.size)
        assertTrue(player.graveyard.any { it === spell })
    }

    @Test
    fun `category filters select only members of that category`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val categoryId = newId()

        player.deck.add(
            inst(
                CardDef(
                    id = newId(), name = "アララギの兵", kind = CardKind.MONSTER,
                    level = 4, atk = 1000, def = 1000, categoryIds = listOf(categoryId)
                )
            )
        )
        player.deck.add(inst(monster("無関係", 4, 1000, 1000)))

        val searcher = inst(
            CardDef(
                id = newId(), name = "サーチ魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                ToHandAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(
                                            CategoryFilter(categoryId),
                                            KindFilter(CardKind.MONSTER)
                                        ),
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        player.hand.add(searcher)

        engine.activateCard(searcher, player)
        assertTrue(player.hand.any { it.card.name == "アララギの兵" })
        assertFalse(player.hand.any { it.card.name == "無関係" })
    }

    @Test
    fun `losing all life ends the duel`() {
        val (state, engine) = freshGame()
        engine.dealDamage(state.players[1], 8000)
        assertTrue(state.finished)
        assertEquals(0, state.winnerIndex)
        assertEquals(0, state.players[1].life)
    }

    @Test
    fun `running out of cards to draw loses the duel`() {
        val (state, engine) = freshGame()
        state.players[0].deck.clear()
        engine.draw(state.players[0], 1)
        assertTrue(state.finished)
        assertEquals(1, state.winnerIndex)
    }
}
