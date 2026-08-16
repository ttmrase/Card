package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class First : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 【制限】の数え方と、儀式召喚、「または」の対象条件。 */
class LimitRitualTest {

    private val valis = "cat-valis"
    private val master = MasterData(categories = listOf(NamedEntry(valis, "VALIS")))

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, First())
    }

    private fun monster(
        name: String,
        level: Int = 4,
        category: String? = null
    ) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = level, atk = 1000, def = 1000,
            categoryIds = listOfNotNull(category)
        )
    )

    /** ①②③がそれぞれライフを回復する、制限付きのモンスター。 */
    private fun threeEffects(name: String, limits: List<UsageLimit>) = CardInstance(
        newId(),
        CardDef(
            id = "three-effects", name = name, kind = CardKind.MONSTER,
            level = 4, atk = 100, def = 100,
            effect = EffectText(
                locations = listOf(ActivationLocation.FIELD),
                limits = limits,
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))),
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 200))),
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 400)))
                )
            )
        )
    )

    // -- 制限 -------------------------------------------------------------

    @Test
    fun `a together limit is enforced at activation, not only in the menu`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = threeEffects(
            "まとめて1度",
            listOf(
                UsageLimit(
                    LimitScope.THIS_CARD, 1,
                    clauseIndices = listOf(1, 2),
                    applies = LimitApplies.TOGETHER
                )
            )
        )
        me.monsterZones[0] = card

        assertTrue(engine.activateClauseForTest(card, 1, me))
        assertEquals(8200, me.life)
        assertFalse("②③はもう発動できない", engine.activateClauseForTest(card, 2, me))
        assertEquals("ライフは動かない", 8200, me.life)
        assertTrue("①は制限の対象外なので使える", engine.activateClauseForTest(card, 0, me))
        assertEquals(8300, me.life)
    }

    @Test
    fun `a same-name limit is shared between copies`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val limits = listOf(
            UsageLimit(LimitScope.SAME_NAME, 1, applies = LimitApplies.TOGETHER)
        )
        val a = threeEffects("同名まとめて", limits)
        val b = threeEffects("同名まとめて", limits)
        me.monsterZones[0] = a
        me.monsterZones[1] = b

        assertTrue(engine.activateClauseForTest(a, 0, me))
        assertFalse("同名なのでもう1枚も使えない", engine.activateClauseForTest(b, 0, me))
        assertEquals(8100, me.life)
    }

    @Test
    fun `an each limit still counts per effect`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = threeEffects(
            "それぞれ1度",
            listOf(UsageLimit(LimitScope.THIS_CARD, 1, applies = LimitApplies.EACH))
        )
        me.monsterZones[0] = card

        assertTrue(engine.activateClauseForTest(card, 0, me))
        assertTrue(engine.activateClauseForTest(card, 1, me))
        assertFalse("①は2度目なので使えない", engine.activateClauseForTest(card, 0, me))
        assertEquals(8300, me.life)
    }

    @Test
    fun `only-one-kind locks out the other effects`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = threeEffects(
            "いずれか1つだけ",
            listOf(
                UsageLimit(
                    LimitScope.THIS_CARD, 2,
                    clauseIndices = listOf(1, 2),
                    applies = LimitApplies.ONLY_ONE_KIND
                )
            )
        )
        me.monsterZones[0] = card

        assertTrue(engine.activateClauseForTest(card, 1, me))
        assertEquals(8200, me.life)
        assertFalse("別の効果は選べない", engine.activateClauseForTest(card, 2, me))
        assertTrue("同じ効果は回数の範囲で使える", engine.activateClauseForTest(card, 1, me))
        assertEquals(8400, me.life)
        assertFalse("3度目は回数を超える", engine.activateClauseForTest(card, 1, me))
    }

    @Test
    fun `only-one-kind is written plainly`() {
        val limit = UsageLimit(
            LimitScope.THIS_CARD, 1,
            clauseIndices = listOf(1, 2),
            applies = LimitApplies.ONLY_ONE_KIND
        )
        assertEquals(
            "②③のうちいずれか1つだけ、1ターンに1度しか発動できない",
            EffectTextRenderer.limitToText(limit, master, cardWide = true)
        )
    }

    // -- 対象の条件の「または」 -------------------------------------------

    @Test
    fun `an or-filter matches either alternative`() {
        val (state, engine) = game()
        val me = state.players[0]

        val valisMonster = monster("VALIS・上級", level = 8, category = valis)
        val lowLevel = monster("下級", level = 3)
        val neither = monster("上級", level = 8)
        me.deck.addAll(listOf(valisMonster, lowLevel, neither))

        val scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.DECK,
            filters = listOf(
                KindFilter(CardKind.MONSTER),
                AnyFilter(listOf(CategoryFilter(valis), LevelFilter(Cmp.LE, 4)))
            ),
            selection = SelectionMode.ALL
        )
        val found = engine.candidates(scope, me)

        assertTrue(found.any { it === valisMonster })
        assertTrue(found.any { it === lowLevel })
        assertFalse("どちらも満たさないものは入らない", found.any { it === neither })
    }

    @Test
    fun `an or-filter reads with matawa`() {
        val scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.DECK,
            filters = listOf(
                KindFilter(CardKind.MONSTER),
                AnyFilter(listOf(CategoryFilter(valis), LevelFilter(Cmp.LE, 4)))
            ),
            count = 1
        )
        assertEquals(
            "自分のデッキの「VALIS」モンスターまたはレベル4以下のモンスター1体",
            EffectTextRenderer.scopeToText(scope, master)
        )
    }

    // -- 儀式召喚 ----------------------------------------------------------

    private fun ritualSpell(requirement: RitualRequirement) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "VALIS・儀式", kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        actions = listOf(
                            RitualSummonAction(
                                summon = CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.HAND,
                                    filters = listOf(
                                        KindFilter(CardKind.MONSTER),
                                        CategoryFilter(valis)
                                    ),
                                    count = 1
                                ),
                                material = CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.FIELD,
                                    filters = listOf(KindFilter(CardKind.MONSTER))
                                ),
                                destination = MoveDestination.GRAVEYARD,
                                requirement = requirement
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `a ritual summon releases enough levels and summons the monster`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val ritualMonster = monster("VALIS・儀式獣", level = 6, category = valis)
        me.hand.add(ritualMonster)
        me.monsterZones[0] = monster("素材A", level = 4)
        me.monsterZones[1] = monster("素材B", level = 3)

        val card = ritualSpell(RitualRequirement.LEVEL_OR_MORE)
        me.hand.add(card)
        assertTrue(engine.activatableCards(me).any { it === card })
        engine.activateCard(card, me)

        assertTrue("儀式モンスターが場に出る", me.monsters.any { it === ritualMonster })
        assertEquals("レベル6を満たすまでリリースする", 2, me.graveyard.count {
            it.card.kind == CardKind.MONSTER
        })
    }

    @Test
    fun `a ritual summon cannot be activated without enough levels`() {
        val (state, engine) = game()
        val me = state.players[0]

        me.hand.add(monster("VALIS・儀式獣", level = 10, category = valis))
        me.monsterZones[0] = monster("素材A", level = 4)

        val card = ritualSpell(RitualRequirement.LEVEL_OR_MORE)
        me.hand.add(card)

        assertFalse(engine.activatableCards(me).any { it === card })
        assertEquals(
            "効果を最後まで処理できる対象がそろっていない。",
            engine.whyCannotActivate(card, me)
        )
    }

    @Test
    fun `an exact-level ritual needs a matching combination`() {
        val (state, engine) = game()
        val me = state.players[0]

        me.hand.add(monster("VALIS・儀式獣", level = 7, category = valis))
        me.monsterZones[0] = monster("素材A", level = 4)
        me.monsterZones[1] = monster("素材B", level = 4)

        val card = ritualSpell(RitualRequirement.LEVEL_EXACT)
        me.hand.add(card)
        assertFalse("4+4 では 7 ちょうどにならない", engine.activatableCards(me).any { it === card })

        me.monsterZones[2] = monster("素材C", level = 3)
        assertTrue("4+3 なら 7 ちょうど", engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `the ritual reads like a ritual spell`() {
        val text = EffectTextRenderer.render(
            ritualSpell(RitualRequirement.LEVEL_OR_MORE).card,
            master
        )
        assertTrue(text, text.contains("レベルの合計がそのモンスターのレベル以上になるように"))
        assertTrue(text, text.contains("墓地へ送ることで"))
        assertTrue(text, text.contains("特殊召喚する"))
    }

    // -- 発動時の効果処理の「できる」 --------------------------------------

    @Test
    fun `an on-activation step can be optional`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        me.deck.add(monster("山札"))

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "発動時に任意", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.ON_ACTIVATION,
                            optionalSteps = listOf(0),
                            actions = listOf(DrawAction(PlayerRef.SELF, 1))
                        )
                    )
                )
            )
        )
        me.hand.add(card)

        val text = EffectTextRenderer.render(card.card, master)
        assertTrue(text, text.contains("このカードの発動時に、"))
        assertTrue(text, text.contains("ドローすることができる"))

        engine.activateCard(card, me)
        assertEquals("受けたのでドローする", 1, me.hand.size)
    }
}
