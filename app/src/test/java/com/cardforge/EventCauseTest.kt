package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class CauseYes : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 「相手の効果によって」のような、出来事の原因を見る条件のテスト。 */
class EventCauseTest {

    private fun game(turnPlayer: Int = 0): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = turnPlayer
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, CauseYes())
    }

    private fun monster(name: String, level: Int = 4) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = level, atk = 1000, def = 1000
        )
    )

    /** 指定した原因でフィールドを離れたときだけライフを回復するモンスター。 */
    private fun watcher(cause: CauseFilter) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "原因を見る者", kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            effect = EffectText(
                locations = listOf(ActivationLocation.GRAVEYARD),
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(
                                event = GameEventType.LEFT_FIELD,
                                selfOnly = true,
                                cause = cause
                            )
                        ),
                        mode = ActivationMode.MANDATORY,
                        actions = listOf(RecoverAction(PlayerRef.SELF, 500))
                    )
                )
            )
        )
    )

    /** 相手のフィールドのモンスター1体を破壊する魔法。 */
    private fun removal() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "破壊の魔法", kind = CardKind.SPELL,
            effect = EffectText(
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
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `an opponent's effect counts as the opponent's effect`() = runBlocking {
        // 魔法は自分のターンにしか発動できないので、相手をターンプレイヤーにする。
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val card = watcher(CauseFilter.BY_OPPONENT_EFFECT)
        me.monsterZones[0] = card

        val spell = removal()
        opponent.hand.add(spell)
        engine.activateCard(spell, opponent)

        assertTrue("相手の効果で破壊された", card in me.graveyard)
        assertEquals("相手の効果が原因なので誘発する", 8500, me.life)
    }

    @Test
    fun `my own effect does not count as the opponent's effect`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = watcher(CauseFilter.BY_OPPONENT_EFFECT)
        me.monsterZones[0] = card

        // 自分の効果で自分のモンスターを破壊する。
        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "自壊の魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                DestroyAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
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

        assertTrue("破壊はされる", card in me.graveyard)
        assertEquals("自分の効果なので誘発しない", 8000, me.life)
    }

    @Test
    fun `the self-effect filter matches my own effect instead`() = runBlocking {
        // 魔法は自分のターンにしか発動できないので、相手をターンプレイヤーにする。
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val mine = watcher(CauseFilter.BY_SELF_EFFECT)
        me.monsterZones[0] = mine

        val spell = removal()
        opponent.hand.add(spell)
        engine.activateCard(spell, opponent)

        assertEquals("相手の効果は「自分の効果によって」に当たらない", 8000, me.life)
    }

    @Test
    fun `battle destruction is not an effect`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val byEffect = watcher(CauseFilter.BY_EFFECT)
        me.monsterZones[0] = byEffect
        engine.destroy(byEffect, byBattle = true)
        assertEquals("戦闘破壊は効果ではない", 8000, me.life)

        val byBattle = watcher(CauseFilter.BY_BATTLE)
        me.monsterZones[0] = byBattle
        engine.destroy(byBattle, byBattle = true)
        assertEquals("戦闘の指定なら誘発する", 8500, me.life)
    }

    @Test
    fun `a cost paid by the opponent is the opponent's effect`() = runBlocking {
        // 魔法は自分のターンにしか発動できないので、相手をターンプレイヤーにする。
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val card = watcher(CauseFilter.BY_OPPONENT_EFFECT)
        opponent.monsterZones[0] = card
        // このカードは相手の場にあるので、持ち主は相手。自分の効果として扱われる。

        val tributing = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "リリースする魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            costs = listOf(TributeCost(count = 1)),
                            actions = listOf(DrawAction(PlayerRef.SELF, 1))
                        )
                    )
                )
            )
        )
        opponent.deck.add(monster("山札"))
        opponent.hand.add(tributing)
        engine.activateCard(tributing, opponent)

        assertTrue("コストでリリースされた", card in opponent.graveyard)
        assertEquals("自分のコストなので「相手の効果」ではない", 8000, opponent.life)
        assertEquals(8000, me.life)
    }

    @Test
    fun `an unqualified condition still reacts to anything`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = watcher(CauseFilter.ANY)
        me.monsterZones[0] = card
        engine.destroy(card, byBattle = true)

        assertEquals("原因を問わないので誘発する", 8500, me.life)
    }

    @Test
    fun `the cause is written into the effect text`() {
        val master = MasterData()
        val card = CardDef(
            id = "x", name = "残響の巨神", kind = CardKind.MONSTER,
            effect = EffectText(
                locations = listOf(ActivationLocation.GRAVEYARD, ActivationLocation.BANISHED),
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(
                                event = GameEventType.LEFT_FIELD,
                                selfOnly = true,
                                cause = CauseFilter.BY_OPPONENT_EFFECT
                            )
                        ),
                        branchMode = BranchMode.FIRST_MATCH,
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
        val lines = EffectTextRenderer.render(card, master).lines()
        assertTrue(
            lines.joinToString("\n"),
            lines[1].contains("このカードが相手の効果によってフィールドを離れた場合")
        )
    }

    @Test
    fun `the whole card works end to end`() = runBlocking {
        // 魔法は自分のターンにしか発動できないので、相手をターンプレイヤーにする。
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val giant = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "残響の巨神", kind = CardKind.MONSTER,
                level = 10, atk = 3000, def = 2800,
                effect = EffectText(
                    locations = listOf(
                        ActivationLocation.GRAVEYARD,
                        ActivationLocation.BANISHED
                    ),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.LEFT_FIELD,
                                    selfOnly = true,
                                    cause = CauseFilter.BY_OPPONENT_EFFECT
                                )
                            ),
                            mode = ActivationMode.OPTIONAL,
                            branchMode = BranchMode.FIRST_MATCH,
                            branches = listOf(
                                EffectBranch(
                                    conditions = listOf(
                                        SelfZoneCondition(listOf(ZoneType.GRAVEYARD))
                                    ),
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
                                    conditions = listOf(
                                        SelfZoneCondition(listOf(ZoneType.BANISHED))
                                    ),
                                    actions = listOf(ToHandAction(CardScope(selfOnly = true)))
                                )
                            )
                        )
                    )
                )
            )
        )
        val big = monster("巨大なる者", level = 10)
        me.deck.add(big)
        me.monsterZones[0] = giant

        val spell = removal()
        opponent.hand.add(spell)
        engine.activateCard(spell, opponent)

        assertTrue("墓地へ送られた", giant in me.graveyard)
        assertTrue("デッキからレベル10以上を手札に加える", me.hand.any { it === big })
        assertFalse("自分自身は手札に戻らない", me.hand.any { it === giant })
    }
}
