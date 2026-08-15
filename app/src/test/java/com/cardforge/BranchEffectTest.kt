package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Accepting : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

class BranchEffectTest {

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Accepting())
    }

    private fun monster(name: String, level: Int = 4) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = level, atk = 1000, def = 1000
        )
    )

    /** 送られた場所で結果が変わるモンスター。 */
    private fun giant() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "残響の巨神", kind = CardKind.MONSTER,
            level = 10, atk = 3000, def = 2800,
            effect = EffectText(
                locations = listOf(ActivationLocation.GRAVEYARD, ActivationLocation.BANISHED),
                clauses = listOf(
                    EffectClause(
                        mode = ActivationMode.OPTIONAL,
                        branches = listOf(
                            EffectBranch(
                                conditions = listOf(SelfZoneCondition(listOf(ZoneType.GRAVEYARD))),
                                actions = listOf(
                                    ToHandAction(
                                        CardScope(
                                            who = PlayerRef.SELF,
                                            zone = ZoneType.DECK,
                                            filters = listOf(
                                                KindFilter(CardKind.MONSTER),
                                                LevelFilter(Cmp.GE, 10)
                                            ),
                                            count = 1
                                        )
                                    )
                                )
                            ),
                            EffectBranch(
                                conditions = listOf(SelfZoneCondition(listOf(ZoneType.BANISHED))),
                                actions = listOf(ToHandAction(CardScope(selfOnly = true)))
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `a branch applies only when its condition holds`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = giant()
        val big = monster("レベル10", level = 10)
        me.deck.add(big)
        me.graveyard.add(card)

        // 墓地にあるので、1つ目の場合分けが適用される。
        engine.activateCard(card, me)
        assertTrue("デッキから加えられる", me.hand.any { it === big })
        assertFalse("自分自身は動かない", me.hand.any { it === card })
    }

    @Test
    fun `the other branch runs when the card is somewhere else`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = giant()
        me.deck.add(monster("レベル10", level = 10))
        me.banished.add(card)

        engine.activateCard(card, me)
        assertTrue("除外ゾーンなら自身が手札に戻る", me.hand.any { it === card })
    }

    @Test
    fun `an effect with no usable branch cannot be activated`() {
        val (state, engine) = game()
        val me = state.players[0]

        val card = giant()
        me.graveyard.add(card)
        // デッキにレベル10以上がいないので、墓地の場合分けは処理できない。
        me.deck.add(monster("レベル4", level = 4))

        assertFalse(engine.activatableCards(me).any { it === card })
        assertEquals(
            "効果を最後まで処理できる対象がそろっていない。",
            engine.whyCannotActivate(card, me)
        )
    }

    @Test
    fun `all matching branches run when the mode says so`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "重ねがけ", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            branchMode = BranchMode.ALL_MATCHING,
                            branches = listOf(
                                EffectBranch(actions = listOf(RecoverAction(PlayerRef.SELF, 100))),
                                EffectBranch(actions = listOf(RecoverAction(PlayerRef.SELF, 200)))
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        assertEquals("条件なしの分岐は全て当てはまる", 8300, me.life)
    }

    @Test
    fun `only the first branch runs by default`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "どれかひとつ", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            branchMode = BranchMode.FIRST_MATCH,
                            branches = listOf(
                                EffectBranch(actions = listOf(RecoverAction(PlayerRef.SELF, 100))),
                                EffectBranch(actions = listOf(RecoverAction(PlayerRef.SELF, 200)))
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        assertEquals(8100, me.life)
    }

    @Test
    fun `leaving the field is an event a card can react to`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val watcher = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "見送り", kind = CardKind.MONSTER,
                atk = 100, def = 100,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.LEFT_FIELD,
                                    who = PlayerRef.OPPONENT
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(RecoverAction(PlayerRef.SELF, 400))
                        )
                    )
                )
            )
        )
        me.hand.add(watcher)

        val victim = monster("消える者")
        opponent.monsterZones[0] = victim
        engine.destroy(victim)

        assertEquals("相手のカードが場を離れたので反応する", 8400, me.life)
    }

    @Test
    fun `branches are written with a bullet for each case`() {
        val master = MasterData()
        val card = CardDef(
            id = "x", name = "場合分け", kind = CardKind.MONSTER,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        branchMode = BranchMode.FIRST_MATCH,
                        branches = listOf(
                            EffectBranch(
                                conditions = listOf(SelfZoneCondition(listOf(ZoneType.GRAVEYARD))),
                                actions = listOf(DrawAction(PlayerRef.SELF, 1))
                            ),
                            EffectBranch(
                                conditions = listOf(SelfZoneCondition(listOf(ZoneType.BANISHED))),
                                actions = listOf(RecoverAction(PlayerRef.SELF, 500))
                            )
                        )
                    )
                )
            )
        )
        val lines = EffectTextRenderer.render(card, master).lines()
        assertTrue(lines[0].contains("以下のうち、最初に当てはまるものを適用する。"))
        assertEquals(
            "●このカードが墓地に存在する場合：自分はカードを1枚ドローする。",
            lines[1]
        )
        assertEquals(
            "●このカードが除外ゾーンに存在する場合：自分のライフを500ポイント回復する。",
            lines[2]
        )
    }
}
