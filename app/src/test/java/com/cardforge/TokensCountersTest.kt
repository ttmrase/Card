package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Picky : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** トークン、カウンター、除外指定、耐性、置き換え、同名処理。 */
class TokensCountersTest {

    private val valis = "cat-valis"
    private val magic = "counter-magic"
    private val master = MasterData(
        categories = listOf(NamedEntry(valis, "VALIS")),
        counters = listOf(NamedEntry(magic, "魔力カウンター"))
    )

    private val tokenDef = CardDef(
        id = "token-1", name = "VALISトークン", kind = CardKind.MONSTER,
        level = 1, atk = 500, def = 500, isToken = true, categoryIds = listOf(valis)
    )

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.tokenDefs = listOf(tokenDef)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Picky())
    }

    private fun monster(
        name: String,
        level: Int = 4,
        category: String? = null,
        atk: Int = 1000
    ) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = level, atk = atk, def = 1000,
            categoryIds = listOfNotNull(category)
        )
    )

    private fun spellWith(name: String, clause: EffectClause) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(clause)
            )
        )
    )

    // -- トークン ----------------------------------------------------------

    @Test
    fun `a token is summoned and vanishes when it leaves the field`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = spellWith(
            "トークン生成",
            EffectClause(actions = listOf(CreateTokenAction("token-1", count = 2)))
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("2体出る", 2, me.monsters.size)
        val token = me.monsters.first()
        engine.destroy(token)

        assertEquals("場から消える", 1, me.monsters.size)
        assertTrue("墓地には残らない", me.graveyard.none { it.card.isToken })
    }

    @Test
    fun `a token cannot be normal summoned`() {
        val (state, engine) = game()
        val me = state.players[0]

        val token = CardInstance(newId(), tokenDef)
        me.hand.add(token)
        assertFalse(engine.canNormalSummon(token, me))
    }

    @Test
    fun `a card marked cannot-normal-summon is refused`() {
        val (state, engine) = game()
        val me = state.players[0]

        val special = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "特殊召喚モンスター", kind = CardKind.MONSTER,
                level = 8, atk = 3000, def = 2000, cannotNormalSummon = true
            )
        )
        me.hand.add(special)
        assertFalse(engine.canNormalSummon(special, me))
    }

    @Test
    fun `special summon can be limited to certain cards`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val locked = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "縛られた者", kind = CardKind.MONSTER,
                level = 8, atk = 3000, def = 2000,
                cannotNormalSummon = true,
                specialSummonOnlyBy = listOf(CategoryFilter(valis))
            )
        )
        me.deck.add(locked)

        fun summoner(name: String, category: String?) = spellWith(
            name,
            EffectClause(
                actions = listOf(
                    SpecialSummonAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.DECK,
                            filters = listOf(KindFilter(CardKind.MONSTER)),
                            count = 1
                        )
                    )
                )
            )
        ).let {
            CardInstance(it.uid, it.card.copy(categoryIds = listOfNotNull(category)))
        }

        val wrong = summoner("よその魔法", null)
        me.hand.add(wrong)
        engine.activateCard(wrong, me)
        assertTrue("条件を満たさない効果では出せない", me.monsters.isEmpty())

        val right = summoner("VALISの魔法", valis)
        me.hand.add(right)
        engine.activateCard(right, me)
        assertTrue("条件を満たす効果なら出せる", me.monsters.any { it === locked })
    }

    // -- カウンター --------------------------------------------------------

    @Test
    fun `counters can be placed, required and spent`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val holder = monster("カウンター置き場")
        me.monsterZones[0] = holder

        val adder = spellWith(
            "カウンターを置く",
            EffectClause(
                actions = listOf(
                    AddCounterAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        ),
                        magic,
                        amount = 3
                    )
                )
            )
        )
        me.hand.add(adder)
        engine.activateCard(adder, me)
        assertEquals(3, holder.counterCount(magic))

        // カウンターが2個以上あるときだけ発動でき、2個を取り除く。
        val spender = spellWith(
            "カウンターを使う",
            EffectClause(
                conditions = listOf(
                    CounterCondition(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        ),
                        magic, Cmp.GE, 2
                    )
                ),
                costs = listOf(
                    CounterCost(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        ),
                        magic, amount = 2
                    )
                ),
                actions = listOf(RecoverAction(PlayerRef.SELF, 600))
            )
        )
        me.hand.add(spender)
        assertTrue(engine.activatableCards(me).any { it === spender })
        engine.activateCard(spender, me)

        assertEquals(8600, me.life)
        assertEquals("2個使った残り", 1, holder.counterCount(magic))
    }

    @Test
    fun `a counter condition blocks activation when short`() {
        val (state, engine) = game()
        val me = state.players[0]
        me.monsterZones[0] = monster("空っぽ")

        val card = spellWith(
            "足りない",
            EffectClause(
                conditions = listOf(
                    CounterCondition(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        ),
                        magic, Cmp.GE, 2
                    )
                ),
                actions = listOf(RecoverAction(PlayerRef.SELF, 100))
            )
        )
        me.hand.add(card)
        assertFalse(engine.activatableCards(me).any { it === card })
    }

    // -- 「〜を除く」 ------------------------------------------------------

    @Test
    fun `exclude-self keeps the source out of the pool`() {
        val (state, engine) = game()
        val me = state.players[0]

        val self = monster("自分自身")
        val other = monster("他の子")
        me.monsterZones[0] = self
        me.monsterZones[1] = other

        val scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.MONSTER_ZONE,
            selection = SelectionMode.ALL,
            excludeSelf = true
        )
        val found = engine.candidates(scope, me, source = self)
        assertEquals(1, found.size)
        assertTrue(found.first() === other)
    }

    @Test
    fun `a not-filter excludes the named card`() {
        val (state, engine) = game()
        val me = state.players[0]

        val keep = monster("VALIS・剣", category = valis)
        val drop = monster("VALIS・盾", category = valis)
        me.deck.addAll(listOf(keep, drop))

        val scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.DECK,
            filters = listOf(CategoryFilter(valis), NotFilter(NameFilter("VALIS・盾"))),
            selection = SelectionMode.ALL
        )
        val found = engine.candidates(scope, me)
        assertEquals(1, found.size)
        assertTrue(found.first() === keep)
    }

    // -- 破壊した数だけ・同名カード ---------------------------------------

    @Test
    fun `a later step can use the count of the previous one`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        opponent.monsterZones[0] = monster("的A")
        opponent.monsterZones[1] = monster("的B")
        repeat(4) { me.deck.add(monster("控え$it", level = 1)) }

        val card = spellWith(
            "破壊した数だけ",
            EffectClause(
                actions = listOf(
                    DestroyAction(
                        CardScope(
                            who = PlayerRef.OPPONENT,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        )
                    ),
                    SpecialSummonAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.DECK,
                            filters = listOf(KindFilter(CardKind.MONSTER)),
                            countSpec = AffectedCountValue(),
                            upTo = true
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("2体破壊した", 0, opponent.monsters.size)
        assertEquals("その数だけ出す", 2, me.monsters.size)
    }

    @Test
    fun `a later step can look for the same name`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val onField = monster("同じ名前")
        me.monsterZones[0] = onField
        me.deck.add(monster("同じ名前"))
        me.deck.add(monster("違う名前"))

        val card = spellWith(
            "破壊して同名を",
            EffectClause(
                actions = listOf(
                    DestroyAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        )
                    ),
                    ToHandAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.DECK,
                            filters = listOf(AffectedNameFilter()),
                            count = 1
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue("同名だけ手札に加わる", me.hand.any { it.card.name == "同じ名前" })
        assertTrue("違う名前は残る", me.deck.any { it.card.name == "違う名前" })
    }

    // -- 耐性 --------------------------------------------------------------

    @Test
    fun `a not-targeted monster is skipped when choosing`() {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val safe = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "狙われない者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                GrantProtectionAction(
                                    CardScope(selfOnly = true),
                                    ProtectionKind.NOT_TARGETED,
                                    from = PlayerRef.OPPONENT
                                )
                            )
                        )
                    )
                )
            )
        )
        val plain = monster("普通の子")
        opponent.monsterZones[0] = safe
        opponent.monsterZones[1] = plain

        val scope = CardScope(
            who = PlayerRef.OPPONENT,
            zone = ZoneType.MONSTER_ZONE,
            count = 1
        )
        val found = engine.candidates(scope, me)
        assertEquals(1, found.size)
        assertTrue(found.first() === plain)

        // 持ち主自身は対象にできる。
        assertTrue(engine.candidates(scope.copy(who = PlayerRef.SELF), opponent).size == 2)
    }

    @Test
    fun `the protection text says whose effects it blocks`() {
        assertEquals(
            "相手の効果を受けない",
            EffectTextRenderer.protectionLabel(
                GrantProtectionAction(CardScope(selfOnly = true), ProtectionKind.OPPONENT_EFFECTS)
            )
        )
        assertEquals(
            "お互いの効果の対象にならない",
            EffectTextRenderer.protectionLabel(
                GrantProtectionAction(
                    CardScope(selfOnly = true),
                    ProtectionKind.NOT_TARGETED,
                    from = PlayerRef.BOTH
                )
            )
        )
    }

    // -- 行き先の差し替え --------------------------------------------------

    @Test
    fun `a destination replacement sends the card elsewhere`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val replacer = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "墓地封じ", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                ReplaceDestinationAction(
                                    CardScope(
                                        who = PlayerRef.BOTH,
                                        zone = ZoneType.MONSTER_ZONE,
                                        selection = SelectionMode.ALL
                                    ),
                                    MoveDestination.BANISHED
                                )
                            )
                        )
                    )
                )
            )
        )
        me.spellTrapZones[0] = replacer

        val victim = monster("消える者")
        me.monsterZones[0] = victim
        engine.destroy(victim)

        assertTrue("墓地ではなく除外ゾーンへ行く", me.banished.any { it === victim })
        assertTrue(me.graveyard.none { it === victim })
    }

    // -- 与える効果に誘発即時 ----------------------------------------------

    @Test
    fun `a granted effect can be quick`() {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.tokenDefs = listOf(tokenDef)
        state.turnPlayerIndex = 1
        state.turn = 3
        state.phase = Phase.MAIN1
        val engine = GameEngine(state, Picky())
        val me = state.players[0]

        val target = monster("受け取る者")
        me.monsterZones[0] = target
        me.spellTrapZones[0] = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "誘発即時を与える", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
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
                                    granted = EffectClause(
                                        locations = listOf(ActivationLocation.FIELD),
                                        quick = true,
                                        actions = listOf(RecoverAction(PlayerRef.SELF, 300))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        assertTrue(
            "相手ターンでも発動できる",
            engine.activatableCards(me).any { it === target }
        )
        assertNull(engine.whyCannotActivate(target, me))
    }
}
