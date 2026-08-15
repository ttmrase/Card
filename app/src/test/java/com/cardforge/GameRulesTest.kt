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
    fun `traps cannot be activated on the turn they were set`() = runBlocking {
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
    fun `spells can only be activated on their controllers turn in a main phase`() = runBlocking {
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

    // --- 【場所】と発動経路 ---------------------------------------------

    @Test
    fun `a spell with no location can be activated straight from the hand`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        // 【場所】を書いていない魔法は「フィールドで発動」＝手札から場に出して発動する。
        val spell = inst(
            CardDef(
                id = newId(), name = "通常魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1))))
                )
            )
        )
        player.hand.add(spell)
        player.deck.add(inst(monster("山札", 4, 100, 100)))

        assertTrue(engine.activatableCards(player).any { it === spell })
        engine.activateCard(spell, player)

        assertTrue("発動後は既定で墓地へ送られる", player.graveyard.any { it === spell })
        assertTrue(player.spellsAndTraps.isEmpty())
        assertTrue(player.hand.any { it.card.name == "山札" })
    }

    @Test
    fun `a set spell can still be activated from the field`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "通常魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        player.hand.add(spell)
        engine.setSpellTrap(spell, player)
        assertTrue(spell.faceDown)

        assertTrue(engine.activatableCards(player).any { it === spell })
        engine.activateCard(spell, player)
        assertEquals(8100, player.life)
        assertTrue(player.graveyard.any { it === spell })
    }

    @Test
    fun `a hand-location spell resolves without taking a spell trap zone`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "手札発動", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 300))))
                )
            )
        )
        player.hand.add(spell)
        // 魔法・罠ゾーンを埋めても、手札で発動する魔法は影響を受けない。
        repeat(5) { index ->
            player.spellTrapZones[index] = inst(
                CardDef(id = newId(), name = "埋め草$index", kind = CardKind.SPELL)
            )
        }
        assertTrue(engine.activatableCards(player).any { it === spell })
        engine.activateCard(spell, player)
        assertEquals(8300, player.life)
        assertTrue(player.graveyard.any { it === spell })
    }

    @Test
    fun `traps must be set before use and cannot be played from the hand`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val trap = inst(
            CardDef(
                id = newId(), name = "普通の罠", kind = CardKind.TRAP,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(actions = listOf(DamageAction(PlayerRef.OPPONENT, 500)))
                    )
                )
            )
        )
        player.hand.add(trap)
        // 魔法と違い、手札から直接は発動できない。
        assertFalse(engine.activatableCards(player).any { it === trap })

        val handTrap = inst(
            CardDef(
                id = newId(), name = "手札の罠", kind = CardKind.TRAP,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(actions = listOf(DamageAction(PlayerRef.OPPONENT, 500)))
                    )
                )
            )
        )
        player.hand.add(handTrap)
        // 【場所】に手札と書いた罠だけは手札から発動できる。
        assertTrue(engine.activatableCards(player).any { it === handTrap })
    }

    // --- 【発動後】 -------------------------------------------------------

    @Test
    fun `a continuous spell stays on the field after resolving`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "永続魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 500))))
                )
            )
        )
        player.hand.add(spell)

        engine.activateCard(spell, player)
        assertEquals(8500, player.life)
        assertTrue("フィールドに残る", player.spellsAndTraps.any { it === spell })
        assertTrue(player.graveyard.isEmpty())
        assertTrue("表側で残る", !spell.faceDown)
    }

    @Test
    fun `after activation can banish the card instead`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "除外魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    afterActivation = AfterActivation.BANISH,
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        player.hand.add(spell)
        engine.activateCard(spell, player)
        assertTrue(player.banished.any { it === spell })
        assertTrue(player.graveyard.isEmpty())
    }

    // --- 【制限】 ---------------------------------------------------------

    @Test
    fun `a per-card limit caps how often one card activates in a turn`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "永続回復", kind = CardKind.SPELL,
                effect = EffectText(
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    limits = listOf(UsageLimit(LimitScope.THIS_CARD, 2)),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        player.hand.add(spell)

        engine.activateCard(spell, player)
        engine.activateCard(spell, player)
        assertEquals(8200, player.life)
        // 3度目は制限に掛かる。
        assertTrue(engine.activatableClauses(spell, player).isEmpty())
        engine.activateCard(spell, player)
        assertEquals(8200, player.life)

        // ターンが変われば回数はリセットされる。
        player.activationsThisTurn.clear()
        assertTrue(engine.activatableClauses(spell, player).isNotEmpty())
    }

    @Test
    fun `a same-name limit is shared between copies of the card`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val definition = CardDef(
            id = newId(), name = "同名制限", kind = CardKind.SPELL,
            effect = EffectText(
                limits = listOf(UsageLimit(LimitScope.SAME_NAME, 1)),
                clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
            )
        )
        val first = inst(definition)
        val second = inst(definition)
        player.hand.addAll(listOf(first, second))

        engine.activateCard(first, player)
        assertEquals(8100, player.life)
        // 別の1枚でも、同名なのでもう発動できない。
        assertTrue(engine.activatableClauses(second, player).isEmpty())
        engine.activateCard(second, player)
        assertEquals(8100, player.life)
    }

    @Test
    fun `a category limit is shared between different cards of that category`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val categoryId = newId()

        fun member(name: String) = inst(
            CardDef(
                id = newId(), name = name, kind = CardKind.SPELL,
                categoryIds = listOf(categoryId),
                effect = EffectText(
                    limits = listOf(UsageLimit(LimitScope.CATEGORY, 1, categoryId)),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        val a = member("カテゴリ魔法A")
        val b = member("カテゴリ魔法B")
        player.hand.addAll(listOf(a, b))

        engine.activateCard(a, player)
        assertEquals(8100, player.life)
        // 名前は違うが同じカテゴリなので、1ターンの枠を共有する。
        assertTrue(engine.activatableClauses(b, player).isEmpty())
    }

    @Test
    fun `a category limit is not consumed by cards that do not declare it`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val categoryId = newId()

        // 制限を書いていない同カテゴリのカード。枠を消費してはいけない。
        val bystander = inst(
            CardDef(
                id = newId(), name = "無制限の仲間", kind = CardKind.SPELL,
                categoryIds = listOf(categoryId),
                effect = EffectText(
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        val limited = inst(
            CardDef(
                id = newId(), name = "制限つき", kind = CardKind.SPELL,
                categoryIds = listOf(categoryId),
                effect = EffectText(
                    limits = listOf(UsageLimit(LimitScope.CATEGORY, 1, categoryId)),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        player.hand.addAll(listOf(bystander, limited))

        engine.activateCard(bystander, player)
        // 制限を宣言していないカードの発動では枠が減らない。
        assertEquals(1, engine.remainingActivations(limited, 0, player))
        assertTrue(engine.activatableClauses(limited, player).isNotEmpty())

        engine.activateCard(limited, player)
        assertEquals(0, engine.remainingActivations(limited, 0, player))
    }

    @Test
    fun `a category limit is shared by every card that declares it`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val categoryId = newId()

        fun member(name: String) = inst(
            CardDef(
                id = newId(), name = name, kind = CardKind.SPELL,
                categoryIds = listOf(categoryId),
                effect = EffectText(
                    limits = listOf(UsageLimit(LimitScope.CATEGORY, 1, categoryId)),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        val a = member("枠を共有A")
        val b = member("枠を共有B")
        player.hand.addAll(listOf(a, b))

        engine.activateCard(a, player)
        assertEquals(8100, player.life)
        // 名前は違っても、同じ制限を宣言しているので枠を共有する。
        assertEquals(0, engine.remainingActivations(b, 0, player))
        assertTrue(engine.activatableClauses(b, player).isEmpty())
    }

    @Test
    fun `a triggered effect does not eat another cards category budget`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val categoryId = newId()

        // 特殊召喚され、召喚時効果を持つ同カテゴリのモンスター。
        val summoned = CardDef(
            id = newId(), name = "カテゴリのモンスター", kind = CardKind.MONSTER,
            level = 3, atk = 1000, def = 1000, categoryIds = listOf(categoryId),
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        timing = EffectTiming.ON_SUMMON,
                        actions = listOf(RecoverAction(PlayerRef.SELF, 100)),
                        limits = listOf(UsageLimit(LimitScope.SAME_NAME, 1))
                    )
                )
            )
        )
        player.deck.add(inst(summoned))

        fun ritual() = inst(
            CardDef(
                id = newId(), name = "儀式", kind = CardKind.SPELL,
                categoryIds = listOf(categoryId),
                effect = EffectText(
                    limits = listOf(UsageLimit(LimitScope.CATEGORY, 1, categoryId)),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                SpecialSummonAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(CategoryFilter(categoryId)),
                                        count = 1
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val first = ritual()
        val second = ritual()
        player.hand.addAll(listOf(first, second))

        engine.activateCard(first, player)
        // 召喚時効果も発動したが、それは儀式の枠を消費しない。
        assertEquals(8100, player.life)
        // 儀式そのものの枠は使い切っているので、2枚目は発動できない。
        assertEquals(0, engine.remainingActivations(second, 0, player))
        assertTrue(engine.activatableClauses(second, player).isEmpty())
    }

    @Test
    fun `a failed cost cancels the activation and returns the card to the hand`() = runBlocking {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 1
        state.phase = Phase.MAIN1
        // コストの選択を拒否する操作者。
        val refusing = object : Interaction {
            override suspend fun chooseCards(
                playerIndex: Int, prompt: String,
                candidates: List<CardInstance>, min: Int, max: Int
            ): List<CardInstance> = emptyList()

            override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
                freeZones.firstOrNull()

            override suspend fun confirm(playerIndex: Int, prompt: String) = true
            override suspend fun chooseOption(
                playerIndex: Int, prompt: String, options: List<String>
            ) = 0
        }
        val engine = GameEngine(state, refusing)
        val player = state.players[0]

        val spell = inst(
            CardDef(
                id = newId(), name = "コスト魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    costs = listOf(DiscardCost(1)),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 500))))
                )
            )
        )
        player.hand.add(spell)
        player.hand.add(inst(monster("捨て札", 4, 100, 100)))

        engine.activateCard(spell, player)
        // 発動は成立しないので、墓地には行かず手札に戻り、制限も消費しない。
        assertTrue(player.hand.any { it === spell })
        assertTrue(player.graveyard.isEmpty())
        assertEquals(8000, player.life)
        assertTrue(player.activationsThisTurn.isEmpty())
    }

    @Test
    fun `a refusal explains itself`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "制限魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    limits = listOf(UsageLimit(LimitScope.THIS_CARD, 1)),
                    clauses = listOf(EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))))
                )
            )
        )
        player.hand.add(spell)
        assertEquals(null, engine.whyCannotActivate(spell, player))

        runBlocking { engine.activateCard(spell, player) }
        assertEquals(
            "【制限】により、このターンはもう発動できない。",
            engine.whyCannotActivate(spell, player)
        )
    }

    @Test
    fun `a per-effect limit only restricts that numbered effect`() = runBlocking {
        val (state, engine) = freshGame()
        val player = state.players[0]
        val spell = inst(
            CardDef(
                id = newId(), name = "二つの効果", kind = CardKind.SPELL,
                effect = EffectText(
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(RecoverAction(PlayerRef.SELF, 100)),
                            limits = listOf(UsageLimit(LimitScope.THIS_CARD, 1))
                        ),
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 200)))
                    )
                )
            )
        )
        player.hand.add(spell)

        assertEquals(listOf(0, 1), engine.activatableClauses(spell, player))
        engine.activateCard(spell, player) // ① を選ぶ（ScriptedInteraction は先頭を選ぶ）
        // ① だけが締め切られ、② はまだ使える。
        assertEquals(listOf(1), engine.activatableClauses(spell, player))
    }

    // --- イベント条件（「〜した場合」）と任意／強制 ------------------------

    @Test
    fun `an event condition fires when the matching event happens`() = runBlocking {
        val (state, engine) = freshGame()
        val me = state.players[0]
        val opponent = state.players[1]

        // 相手がモンスターを召喚した場合に発動する、手札のモンスター。
        val handTrap = inst(
            CardDef(
                id = newId(), name = "手札誘発", kind = CardKind.MONSTER,
                level = 2, atk = 800, def = 600,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    costs = listOf(DiscardSelfCost()),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.SUMMONED,
                                    who = PlayerRef.OPPONENT,
                                    filters = listOf(KindFilter(CardKind.MONSTER))
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(DamageAction(PlayerRef.OPPONENT, 700))
                        )
                    )
                )
            )
        )
        me.hand.add(handTrap)

        // 誘発効果は手動では発動できない。
        assertFalse(engine.activatableCards(me).any { it === handTrap })

        // 相手のターンに、相手がモンスターを召喚する。
        state.turnPlayerIndex = 1
        val summoned = inst(monster("相手モンスター", 4, 1500, 1000))
        opponent.hand.add(summoned)
        engine.normalSummon(summoned, opponent, asSet = false)

        assertEquals("イベントで誘発効果が発動する", 8000 - 700, opponent.life)
        // コストとして墓地へ送られている。
        assertTrue(me.graveyard.any { it === handTrap })
        assertFalse(me.hand.any { it === handTrap })
    }

    @Test
    fun `an event condition ignores events from the wrong side`() = runBlocking {
        val (state, engine) = freshGame()
        val me = state.players[0]

        val watcher = inst(
            CardDef(
                id = newId(), name = "見張り", kind = CardKind.MONSTER,
                level = 2, atk = 800, def = 600,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.SUMMONED,
                                    who = PlayerRef.OPPONENT
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(DamageAction(PlayerRef.OPPONENT, 500))
                        )
                    )
                )
            )
        )
        me.hand.add(watcher)

        // 自分が召喚しても、相手の召喚を条件にした効果は誘発しない。
        val own = inst(monster("自分のモンスター", 4, 1500, 1000))
        me.hand.add(own)
        engine.normalSummon(own, me, asSet = false)
        assertEquals(8000, state.players[1].life)
    }

    @Test
    fun `a trap set this turn does not fire its triggered effect either`() = runBlocking {
        val (state, engine) = freshGame()
        val me = state.players[0]
        val opponent = state.players[1]

        val trap = inst(
            CardDef(
                id = newId(), name = "誘発罠", kind = CardKind.TRAP,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.SUMMONED,
                                    who = PlayerRef.OPPONENT
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(DamageAction(PlayerRef.OPPONENT, 500))
                        )
                    )
                )
            )
        )
        me.hand.add(trap)
        engine.setSpellTrap(trap, me)

        // 伏せたターンは誘発効果も発動しない。
        state.turnPlayerIndex = 1
        val first = inst(monster("1体目", 4, 1500, 1000))
        opponent.hand.add(first)
        engine.normalSummon(first, opponent, asSet = false)
        assertEquals(8000, opponent.life)

        // 次のターン以降は発動する。
        state.turn = 2
        opponent.normalSummonUsed = false
        val second = inst(monster("2体目", 4, 1500, 1000))
        opponent.hand.add(second)
        engine.normalSummon(second, opponent, asSet = false)
        assertEquals(8000 - 500, opponent.life)
    }

    @Test
    fun `an optional effect asks first and a mandatory one does not`() = runBlocking {
        var confirmations = 0
        val declining = object : Interaction {
            override suspend fun chooseCards(
                playerIndex: Int, prompt: String,
                candidates: List<CardInstance>, min: Int, max: Int
            ) = if (min == 0) emptyList() else candidates.take(maxOf(min, 1).coerceAtMost(max))

            override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
                freeZones.firstOrNull()

            override suspend fun confirm(playerIndex: Int, prompt: String): Boolean {
                confirmations++
                return false
            }

            override suspend fun chooseOption(
                playerIndex: Int, prompt: String, options: List<String>
            ) = 0
        }
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 1
        state.phase = Phase.MAIN1
        val engine = GameEngine(state, declining)
        val me = state.players[0]

        fun burner(mode: ActivationMode) = inst(
            CardDef(
                id = newId(), name = "焼き$mode", kind = CardKind.MONSTER,
                level = 2, atk = 100, def = 100,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(GameEventType.SUMMONED, who = PlayerRef.SELF)
                            ),
                            mode = mode,
                            actions = listOf(DamageAction(PlayerRef.OPPONENT, 300))
                        )
                    )
                )
            )
        )
        val optional = burner(ActivationMode.OPTIONAL)
        me.hand.add(optional)
        val trigger = inst(monster("引き金", 4, 1500, 1000))
        me.hand.add(trigger)
        engine.normalSummon(trigger, me, asSet = false)

        assertTrue("任意効果は確認を取る", confirmations > 0)
        assertEquals("断ったので発動しない", 8000, state.players[1].life)

        // 強制効果は確認せずに発動する。断った任意効果は手札に残るので取り除く。
        me.hand.removeAll { it === optional }
        me.normalSummonUsed = false
        me.activationsThisTurn.clear()
        me.hand.add(burner(ActivationMode.MANDATORY))
        val trigger2 = inst(monster("引き金2", 4, 1500, 1000))
        me.hand.add(trigger2)
        val before = confirmations
        engine.normalSummon(trigger2, me, asSet = false)
        assertEquals("強制効果は確認しない", before, confirmations)
        assertEquals(8000 - 300, state.players[1].life)
    }

    // --- 手札誘発のコストと発動後の区別 -----------------------------------

    @Test
    fun `a self cost sends the card away before the effect resolves`() = runBlocking {
        val (state, engine) = freshGame()
        val me = state.players[0]
        val card = inst(
            CardDef(
                id = newId(), name = "コスト型", kind = CardKind.MONSTER,
                level = 2, atk = 100, def = 100,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    costs = listOf(DiscardSelfCost()),
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 200)))
                    )
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        assertEquals(8200, me.life)
        assertTrue(me.graveyard.any { it === card })
    }

    @Test
    fun `after activation sends a hand monster away once it has resolved`() = runBlocking {
        val (state, engine) = freshGame()
        val me = state.players[0]
        val card = inst(
            CardDef(
                id = newId(), name = "発動後型", kind = CardKind.MONSTER,
                level = 2, atk = 100, def = 100,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    afterActivation = AfterActivation.TO_GRAVE,
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 200)))
                    )
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        assertEquals(8200, me.life)
        assertTrue(me.graveyard.any { it === card })
    }

    @Test
    fun `a monster with no after activation stays where it is`() = runBlocking {
        val (state, engine) = freshGame()
        val me = state.players[0]
        val card = inst(
            CardDef(
                id = newId(), name = "居座り", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100)))
                    )
                )
            )
        )
        me.monsterZones[0] = card

        engine.activateCard(card, me)
        assertEquals(8100, me.life)
        // 【発動後】を省略したモンスターは場に残る。
        assertTrue(me.monsters.any { it === card })
        assertTrue(me.graveyard.isEmpty())
    }

    @Test
    fun `losing all life ends the duel`() = runBlocking {
        val (state, engine) = freshGame()
        engine.dealDamage(state.players[1], 8000)
        assertTrue(state.finished)
        assertEquals(0, state.winnerIndex)
        assertEquals(0, state.players[1].life)
    }

    @Test
    fun `running out of cards to draw loses the duel`() = runBlocking {
        val (state, engine) = freshGame()
        state.players[0].deck.clear()
        engine.draw(state.players[0], 1)
        assertTrue(state.finished)
        assertEquals(1, state.winnerIndex)
    }
}
