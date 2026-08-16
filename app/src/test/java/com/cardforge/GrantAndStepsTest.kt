package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Replying(private val answer: Boolean) : Interaction {
    val confirms = mutableListOf<String>()

    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String): Boolean {
        confirms += prompt
        return answer
    }

    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 効果の付与と、まとめた処理のテスト。 */
class GrantAndStepsTest {

    private val valis = "cat-valis"
    private val master = MasterData(categories = listOf(NamedEntry(valis, "VALIS")))

    private fun game(interaction: Interaction = Replying(true)): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, interaction)
    }

    private fun monster(name: String, category: String? = null, atk: Int = 1000) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = atk, def = 1000,
            categoryIds = listOfNotNull(category)
        )
    )

    private fun spell(name: String, category: String? = null) = CardInstance(
        newId(),
        CardDef(id = newId(), name = name, kind = CardKind.SPELL, categoryIds = listOfNotNull(category))
    )

    // -- 効果の付与 -------------------------------------------------------

    /** 自分フィールドのモンスターに、起動効果を与える永続魔法。 */
    private fun granter(granted: EffectClause) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "力を与えるもの", kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.FIELD),
                afterActivation = AfterActivation.STAY_ON_FIELD,
                clauses = listOf(
                    EffectClause(
                        mode = ActivationMode.CONTINUOUS,
                        actions = listOf(
                            GrantEffectAction(
                                scope = CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.MONSTER_ZONE,
                                    selection = SelectionMode.ALL
                                ),
                                granted = granted
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `a granted ignition effect can be activated by the receiver`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val target = monster("受け取る者")
        me.monsterZones[0] = target
        me.spellTrapZones[0] = granter(
            EffectClause(
                locations = listOf(ActivationLocation.FIELD),
                actions = listOf(RecoverAction(PlayerRef.SELF, 700))
            )
        )

        assertTrue(
            "与えられた効果で発動できるようになる",
            engine.activatableCards(me).any { it === target }
        )
        assertTrue(engine.activateCard(target, me))
        assertEquals(8700, me.life)
    }

    @Test
    fun `the effect disappears when the granting card leaves`() {
        val (state, engine) = game()
        val me = state.players[0]

        val target = monster("受け取る者")
        me.monsterZones[0] = target
        val source = granter(
            EffectClause(
                locations = listOf(ActivationLocation.FIELD),
                actions = listOf(RecoverAction(PlayerRef.SELF, 700))
            )
        )
        me.spellTrapZones[0] = source
        assertTrue(engine.activatableCards(me).any { it === target })

        me.spellTrapZones[0] = null
        assertFalse("与え手が居なくなれば効果も消える", engine.activatableCards(me).any { it === target })
    }

    @Test
    fun `a granted continuous effect applies too`() {
        val (state, engine) = game()
        val me = state.players[0]

        val target = monster("受け取る者", atk = 1000)
        me.monsterZones[0] = target
        me.spellTrapZones[0] = granter(
            EffectClause(
                locations = listOf(ActivationLocation.FIELD),
                mode = ActivationMode.CONTINUOUS,
                actions = listOf(
                    ModifyStatAction(
                        CardScope(selfOnly = true),
                        StatKind.ATK,
                        deltaValue = FixedValue(800)
                    )
                )
            )
        )

        assertEquals("与えられた永続効果も働く", 1800, engine.atkOf(target))
    }

    @Test
    fun `granting an effect that grants effects is refused`() {
        val (state, engine) = game()
        val me = state.players[0]

        val target = monster("受け取る者")
        me.monsterZones[0] = target
        me.spellTrapZones[0] = granter(
            EffectClause(
                mode = ActivationMode.CONTINUOUS,
                actions = listOf(
                    GrantEffectAction(
                        scope = CardScope(selfOnly = true),
                        granted = EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100)))
                    )
                )
            )
        )

        assertFalse(
            "効果を与える効果は与えられない",
            engine.activatableCards(me).any { it === target }
        )
    }

    @Test
    fun `the grant is written as gaining an effect`() {
        val text = EffectTextRenderer.render(
            granter(
                EffectClause(
                    locations = listOf(ActivationLocation.FIELD),
                    actions = listOf(RecoverAction(PlayerRef.SELF, 700))
                )
            ).card,
            master
        )
        assertTrue(text, text.contains("次の効果を得る"))
        assertTrue(text, text.contains("自分のライフを700ポイント回復する"))
    }

    // -- まとめた処理 -----------------------------------------------------

    /** 「手札を相手に見せ、デッキから墓地へ送ることができる」カード。 */
    private fun herald() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "VALIS・号令", kind = CardKind.SPELL,
            categoryIds = listOf(valis),
            effect = EffectText(
                locations = listOf(ActivationLocation.FIELD),
                clauses = listOf(
                    EffectClause(
                        optionalSteps = listOf(1),
                        linkedSteps = listOf(2),
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
                            ToGraveAction(
                                CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.DECK,
                                    filters = listOf(
                                        CategoryFilter(valis),
                                        KindFilter(CardKind.MONSTER)
                                    ),
                                    countSpec = CountValue(
                                        scope = CardScope(
                                            who = PlayerRef.SELF,
                                            zone = ZoneType.HAND,
                                            filters = listOf(
                                                CategoryFilter(valis),
                                                KindFilter(CardKind.SPELL)
                                            ),
                                            selection = SelectionMode.ALL
                                        ),
                                        multiplier = 1
                                    ),
                                    upTo = true
                                )
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `declining the linked unit skips both the reveal and the mill`() = runBlocking {
        val declining = Replying(false)
        val (state, engine) = game(declining)
        val me = state.players[0]

        me.deck.add(spell("VALIS・デッキの魔法", valis))
        repeat(4) { me.deck.add(monster("VALIS・モンスター$it", valis)) }

        val card = herald()
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue("サーチは強制なので通る", me.hand.any { it.card.name == "VALIS・デッキの魔法" })
        assertEquals("断ったので墓地送りもしない", 0, me.graveyard.count {
            it.card.kind == CardKind.MONSTER
        })
        assertFalse("見せてもいない", state.log.any { it.contains("相手に見せた") })
        assertEquals("確認は1回だけ", 1, declining.confirms.size)
        assertTrue(
            declining.confirms.first(),
            declining.confirms.first().contains("見せ、")
        )
    }

    @Test
    fun `accepting the linked unit does both`() = runBlocking {
        val (state, engine) = game(Replying(true))
        val me = state.players[0]

        me.deck.add(spell("VALIS・デッキの魔法", valis))
        repeat(4) { me.deck.add(monster("VALIS・モンスター$it", valis)) }

        val card = herald()
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue("見せている", state.log.any { it.contains("相手に見せた") })
        assertEquals("手札の1枚ぶん墓地へ送る", 1, me.graveyard.count {
            it.card.kind == CardKind.MONSTER
        })
    }

    @Test
    fun `an empty hand does not break the unit`() = runBlocking {
        val (state, engine) = game(Replying(true))
        val me = state.players[0]

        // デッキに「VALIS」魔法が無いので、サーチしても手札は増えない。
        repeat(4) { me.deck.add(monster("VALIS・モンスター$it", valis)) }

        val card = herald()
        me.hand.add(card)
        // サーチ対象がいないので発動できないことを確かめてから、対象を用意する。
        assertFalse(engine.activatableCards(me).any { it === card })

        me.deck.add(spell("VALIS・デッキの魔法", valis))
        assertTrue(engine.activatableCards(me).any { it === card })
        engine.activateCard(card, me)

        // 手札は加えた1枚だけ（発動したこのカードは場に出ている）。
        assertTrue("見せる処理は落ちない", state.log.any { it.contains("相手に見せた") })
        assertEquals(1, me.graveyard.count { it.card.kind == CardKind.MONSTER })
    }

    @Test
    fun `the linked steps read as one sentence`() {
        val text = EffectTextRenderer.render(herald().card, master)
        val line = text.lines().first { it.startsWith("①") }

        assertTrue(line, line.contains("相手に見せ、"))
        assertTrue(line, line.endsWith("墓地へ送ることができる。"))
        assertFalse("2つに分かれていない", line.contains("見せる。その後、"))
    }

    // -- 枚数を参照するドローなど -----------------------------------------

    @Test
    fun `mill and discard can follow a count too`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        repeat(3) { me.graveyard.add(monster("墓地$it")) }
        repeat(8) { me.deck.add(monster("山札$it")) }
        repeat(5) { opponent.hand.add(monster("相手の手札$it")) }

        val counting = CountValue(
            scope = CardScope(
                who = PlayerRef.SELF,
                zone = ZoneType.GRAVEYARD,
                selection = SelectionMode.ALL
            ),
            multiplier = 1
        )
        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "墓地の数だけ", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                MillAction(PlayerRef.SELF, countValue = counting),
                                DiscardAction(
                                    PlayerRef.OPPONENT,
                                    random = true,
                                    countValue = FixedValue(2)
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        // 墓地のモンスター3枚ぶん落ちて、元の3枚と合わせて6枚。
        assertEquals(6, me.graveyard.count { it.card.kind == CardKind.MONSTER })
        assertEquals(3, opponent.hand.size)
    }
}
