package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TakeAll : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(max, 0))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 枚数参照・召喚制限・公開のテスト。 */
class CountRestrictRevealTest {

    private val valis = "cat-valis"
    private val master = MasterData(categories = listOf(NamedEntry(valis, "VALIS")))

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, TakeAll())
    }

    private fun monster(name: String, category: String? = null) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            categoryIds = listOfNotNull(category)
        )
    )

    private fun spell(name: String, category: String? = null) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.SPELL,
            categoryIds = listOfNotNull(category)
        )
    )

    /** 手札の「VALIS」魔法カードの数だけ、デッキから「VALIS」モンスターを墓地へ送る指定。 */
    private fun millByHandCount() = ToGraveAction(
        CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.DECK,
            filters = listOf(CategoryFilter(valis), KindFilter(CardKind.MONSTER)),
            countSpec = CountValue(
                scope = CardScope(
                    who = PlayerRef.SELF,
                    zone = ZoneType.HAND,
                    filters = listOf(CategoryFilter(valis), KindFilter(CardKind.SPELL)),
                    selection = SelectionMode.ALL
                ),
                multiplier = 1
            ),
            upTo = true
        )
    )

    /** お題のカードそのもの。 */
    private fun herald() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "VALIS・号令", kind = CardKind.SPELL,
            categoryIds = listOf(valis),
            effect = EffectText(
                locations = listOf(ActivationLocation.FIELD),
                summonLocks = listOf(
                    SummonLock(
                        who = PlayerRef.SELF,
                        summon = SummonKind.SPECIAL,
                        filters = listOf(CategoryFilter(valis)),
                        except = true
                    )
                ),
                clauses = listOf(
                    EffectClause(
                        optionalSteps = listOf(2),
                        actions = listOf(
                            ToHandAction(
                                CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.DECK,
                                    filters = listOf(
                                        CategoryFilter(valis),
                                        KindFilter(CardKind.SPELL)
                                    ),
                                    count = 1
                                )
                            ),
                            RevealAction(
                                CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.HAND,
                                    selection = SelectionMode.ALL
                                )
                            ),
                            millByHandCount()
                        )
                    )
                )
            )
        )
    )

    // -- 枚数参照 ---------------------------------------------------------

    @Test
    fun `the number sent to the graveyard follows another zone's count`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        // 手札に「VALIS」魔法が2枚。
        me.hand.add(spell("VALIS・魔法A", valis))
        me.hand.add(spell("VALIS・魔法B", valis))
        me.hand.add(spell("関係ない魔法"))
        repeat(5) { me.deck.add(monster("VALIS・モンスター$it", valis)) }

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "数だけ落とす", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(EffectClause(actions = listOf(millByHandCount())))
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        assertEquals("手札の「VALIS」魔法2枚ぶんだけ落ちる", 2, me.graveyard.count {
            it.card.kind == CardKind.MONSTER
        })
    }

    @Test
    fun `an up-to count does not block activation when the deck is short`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        me.hand.add(spell("VALIS・魔法A", valis))
        me.hand.add(spell("VALIS・魔法B", valis))
        // デッキには1体しかいない。
        me.deck.add(monster("VALIS・モンスター", valis))

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "数だけ落とす", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(EffectClause(actions = listOf(millByHandCount())))
                )
            )
        )
        me.hand.add(card)

        assertTrue(
            "「〜まで」なので足りなくても発動できる",
            engine.activatableCards(me).any { it === card }
        )
        engine.activateCard(card, me)
        assertEquals(1, me.graveyard.count { it.card.kind == CardKind.MONSTER })
    }

    @Test
    fun `a draw can also follow a count`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        repeat(3) { me.graveyard.add(monster("墓地$it")) }
        repeat(5) { me.deck.add(monster("山札$it")) }

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "墓地の数だけドロー", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                DrawAction(
                                    PlayerRef.SELF,
                                    countValue = CountValue(
                                        scope = CardScope(
                                            who = PlayerRef.SELF,
                                            zone = ZoneType.GRAVEYARD,
                                            selection = SelectionMode.ALL
                                        ),
                                        multiplier = 1
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        assertEquals("墓地3枚ぶんドローする", 3, me.hand.size)
    }

    // -- 召喚制限 ---------------------------------------------------------

    @Test
    fun `a summon restriction blocks everything but the named category`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        me.deck.add(spell("VALIS・魔法", valis))
        val card = herald()
        me.hand.add(card)
        engine.activateCard(card, me)

        val valisMonster = monster("VALIS・モンスター", valis)
        val other = monster("よその子")
        me.deck.add(valisMonster)
        me.deck.add(other)

        assertFalse(
            "「VALIS」以外は特殊召喚できない",
            engine.summonAllowed(other, me, SummonKind.SPECIAL)
        )
        assertTrue(
            "「VALIS」なら出せる",
            engine.summonAllowed(valisMonster, me, SummonKind.SPECIAL)
        )
        assertTrue(
            "通常召喚は縛られない",
            engine.summonAllowed(other, me, SummonKind.NORMAL)
        )
    }

    @Test
    fun `the restriction is lifted when the turn changes`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        me.deck.add(spell("VALIS・魔法", valis))
        me.hand.add(herald())
        engine.activateCard(me.hand.first { it.card.name == "VALIS・号令" }, me)

        val other = monster("よその子")
        assertFalse(engine.summonAllowed(other, me, SummonKind.SPECIAL))

        engine.endTurnImmediately()
        assertTrue("次のターンには消えている", engine.summonAllowed(other, me, SummonKind.SPECIAL))
    }

    // -- 公開 -------------------------------------------------------------

    @Test
    fun `revealing for the turn keeps the card visible`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val a = spell("公開されるカード")
        me.hand.add(a)

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "手札公開", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                RevealAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.HAND,
                                        selection = SelectionMode.ALL
                                    ),
                                    RevealDuration.TURN
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue("このターンは見えている", a.isRevealed(state.turn))
        assertFalse("次のターンには隠れる", a.isRevealed(state.turn + 1))
    }

    @Test
    fun `a momentary reveal leaves no trace`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val a = spell("見せるだけ")
        me.hand.add(a)

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "見せる", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                RevealAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.HAND,
                                        selection = SelectionMode.ALL
                                    ),
                                    RevealDuration.MOMENT
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertFalse(a.isRevealed(state.turn))
        assertTrue(
            "見せたことはログに残る",
            state.log.any { it.contains("相手に見せた") }
        )
    }

    // -- 通しで再現できるか -----------------------------------------------

    @Test
    fun `the whole card works end to end`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        me.hand.add(spell("VALIS・手札の魔法", valis))
        me.deck.add(spell("VALIS・デッキの魔法", valis))
        repeat(4) { me.deck.add(monster("VALIS・モンスター$it", valis)) }

        val card = herald()
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue(
            "デッキから「VALIS」魔法を手札に加える",
            me.hand.any { it.card.name == "VALIS・デッキの魔法" }
        )
        // 手札の「VALIS」魔法は加えた分を含めて2枚になっている。
        assertEquals("その数だけ墓地へ送る", 2, me.graveyard.count {
            it.card.kind == CardKind.MONSTER
        })
        assertFalse(
            "「VALIS」以外は特殊召喚できない",
            engine.summonAllowed(monster("よその子"), me, SummonKind.SPECIAL)
        )
    }

    @Test
    fun `the card text reads like the original`() {
        val text = EffectTextRenderer.render(herald().card, master)
        val lines = text.lines()

        assertTrue(text, lines.any { it.startsWith("【制限】このカードを発動するターン") })
        assertTrue(
            text,
            lines.any { it.contains("「VALIS」モンスター以外のモンスターを特殊召喚できない") }
        )
        assertTrue(text, lines.any { it.contains("相手に見せる") })
        assertTrue(
            text,
            lines.any { it.contains("自分の手札の「VALIS」魔法カードの数まで、") }
        )
        assertTrue(text, lines.none { it.startsWith("②") })
        assertTrue(text, lines.any { it.startsWith("①：自分のデッキの「VALIS」魔法カード") })
        assertTrue(text, lines.any { it.contains("墓地へ送ることができる") })
    }
}
