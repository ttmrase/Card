package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class PickFirst : Interaction {
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

class CostAndResolutionTest {

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, PickFirst())
    }

    private fun monster(name: String) = CardInstance(
        newId(),
        CardDef(id = newId(), name = name, kind = CardKind.MONSTER, atk = 1000, def = 1000)
    )

    private fun spell(name: String, effect: EffectText) =
        CardInstance(newId(), CardDef(id = newId(), name = name, kind = CardKind.SPELL, effect = effect))

    // --- 自由に組めるコスト ------------------------------------------------

    @Test
    fun `a cost can send graveyard cards back to the deck`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val buried = monster("眠る者")
        me.graveyard.add(buried)
        me.deck.add(monster("山札"))

        val card = spell(
            "還元の儀",
            EffectText(
                locations = listOf(ActivationLocation.HAND),
                costs = listOf(
                    MoveCost(
                        scope = CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.GRAVEYARD,
                            count = 1,
                            selection = SelectionMode.CHOOSE
                        ),
                        destination = MoveDestination.DECK_TOP
                    )
                ),
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 300)))
                )
            )
        )
        me.hand.add(card)

        assertTrue(engine.activatableCards(me).any { it === card })
        engine.activateCard(card, me)

        assertEquals(8300, me.life)
        assertTrue("墓地のカードがデッキに戻る", me.deck.any { it === buried })
        assertFalse(me.graveyard.any { it === buried })
    }

    @Test
    fun `a cost cannot be paid when nothing matches it`() {
        val (state, engine) = game()
        val me = state.players[0]

        val card = spell(
            "還元の儀",
            EffectText(
                locations = listOf(ActivationLocation.HAND),
                costs = listOf(
                    MoveCost(
                        scope = CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.GRAVEYARD,
                            filters = listOf(KindFilter(CardKind.MONSTER)),
                            count = 2
                        ),
                        destination = MoveDestination.DECK_TOP
                    )
                ),
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 300)))
                )
            )
        )
        me.hand.add(card)

        // 墓地が1体では足りない。
        me.graveyard.add(monster("1体目"))
        assertFalse(engine.activatableCards(me).any { it === card })
        assertEquals("【コスト】を支払えない。", engine.whyCannotActivate(card, me))

        me.graveyard.add(monster("2体目"))
        assertTrue(engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `a move cost uses the target picker in its wording`() {
        val cost = MoveCost(
            scope = CardScope(
                who = PlayerRef.SELF,
                zone = ZoneType.GRAVEYARD,
                filters = listOf(KindFilter(CardKind.MONSTER)),
                count = 1
            ),
            destination = MoveDestination.DECK_TOP
        )
        assertEquals(
            "自分の墓地のモンスター1体をデッキの一番上に戻す",
            EffectTextRenderer.costToText(cost, MasterData())
        )
    }

    // --- 効果を最後まで処理できること --------------------------------------

    @Test
    fun `an effect needs every step to have a target`() {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val card = spell(
            "二段構え",
            EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        actions = listOf(
                            DestroyAction(
                                CardScope(
                                    who = PlayerRef.OPPONENT,
                                    zone = ZoneType.MONSTER_ZONE,
                                    count = 1
                                )
                            ),
                            BanishAction(
                                CardScope(
                                    who = PlayerRef.OPPONENT,
                                    zone = ZoneType.MONSTER_ZONE,
                                    count = 1
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)

        // 相手モンスターが1体では、2つ目の処理まで届かない。
        opponent.monsterZones[0] = monster("1体目")
        assertFalse(engine.activatableCards(me).any { it === card })
        assertEquals(
            "効果を最後まで処理できる対象がそろっていない。",
            engine.whyCannotActivate(card, me)
        )

        // 2体いれば、順に処理できるので発動できる。
        opponent.monsterZones[1] = monster("2体目")
        assertTrue(engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `an effect with no target at all cannot be activated`() {
        val (state, engine) = game()
        val me = state.players[0]

        val card = spell(
            "破壊のみ",
            EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        actions = listOf(
                            DestroyAction(
                                CardScope(
                                    who = PlayerRef.OPPONENT,
                                    zone = ZoneType.MONSTER_ZONE,
                                    selection = SelectionMode.ALL
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)

        assertFalse("相手が空なら撃てない", engine.activatableCards(me).any { it === card })
        state.players[1].monsterZones[0] = monster("的")
        assertTrue(engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `steps that do not target cards never block activation`() {
        val (state, engine) = game()
        val me = state.players[0]
        me.deck.add(monster("山札"))

        val card = spell(
            "撃って引く",
            EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        actions = listOf(
                            DamageAction(PlayerRef.OPPONENT, 300),
                            DrawAction(PlayerRef.SELF, 1)
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        assertTrue(engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `a triggered effect also needs its targets`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        // 相手が召喚したら相手モンスター2体を破壊する、という強制効果。
        val watcher = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "見張り", kind = CardKind.MONSTER,
                atk = 100, def = 100,
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
                            actions = listOf(
                                DestroyAction(
                                    CardScope(
                                        who = PlayerRef.OPPONENT,
                                        zone = ZoneType.MONSTER_ZONE,
                                        count = 2
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(watcher)

        state.turnPlayerIndex = 1
        val summoned = monster("召喚された者")
        opponent.hand.add(summoned)
        engine.normalSummon(summoned, opponent, asSet = false)

        // 相手モンスターが1体しかいないので、誘発効果も発動しない。
        assertTrue(opponent.monsters.any { it === summoned })
        assertTrue(opponent.graveyard.isEmpty())
    }
}
