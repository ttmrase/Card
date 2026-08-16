package com.cardforge

import com.cardforge.data.LegacyMigration
import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Yes2 : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 「〜できない」制限を、【制限】と効果の両方で確かめる。 */
class RestrictionTest {

    private val valis = "cat-valis"
    private val master = MasterData(categories = listOf(NamedEntry(valis, "VALIS")))

    private fun game(turnPlayer: Int = 0): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Yes2())
    }

    private fun monster(name: String, category: String? = null, atk: Int = 1500) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = atk, def = 1000,
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

    private fun restricting(
        name: String,
        kind: RestrictionKind,
        who: PlayerRef = PlayerRef.OPPONENT,
        duration: RestrictionDuration = RestrictionDuration.THIS_TURN,
        filters: List<CardFilter> = emptyList(),
        except: Boolean = false
    ) = spellWith(
        name,
        EffectClause(actions = listOf(RestrictAction(who, kind, filters, except, duration)))
    )

    // -- 効果として掛ける ---------------------------------------------------

    @Test
    fun `an effect can stop the opponent from attacking directly`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val card = restricting("直接攻撃封じ", RestrictionKind.DIRECT_ATTACK)
        me.hand.add(card)
        engine.activateCard(card, me)

        // 相手のターンにして、相手のモンスターで直接攻撃を試す。
        val attacker = monster("殴る者")
        opponent.monsterZones[0] = attacker
        attacker.summonedOnTurn = state.turn - 1
        state.turnPlayerIndex = 1
        state.phase = Phase.BATTLE

        assertTrue("攻撃自体はできる", engine.canAttack(attacker))
        assertFalse("直接攻撃はできない", engine.canAttackDirectlyWith(attacker))

        engine.declareAttack(attacker, null)
        assertEquals("ライフは減らない", 8000, me.life)
    }

    @Test
    fun `an effect can stop position changes`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val standing = monster("動けない者")
        me.monsterZones[0] = standing
        standing.summonedOnTurn = state.turn - 1
        assertTrue(engine.canChangePosition(standing, me))

        val card = restricting("表示形式を固定", RestrictionKind.CHANGE_POSITION, PlayerRef.SELF)
        me.hand.add(card)
        engine.activateCard(card, me)

        assertFalse("表示形式を変更できない", engine.canChangePosition(standing, me))
    }

    @Test
    fun `an effect can stop spells from being activated`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = restricting("魔法封じ", RestrictionKind.ACTIVATE_SPELL, PlayerRef.SELF)
        me.hand.add(card)
        engine.activateCard(card, me)

        val other = spellWith(
            "普通の魔法",
            EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100)))
        )
        me.hand.add(other)

        assertFalse(engine.activatableCards(me).any { it === other })
        assertEquals(
            "【制限】により、このカードの効果は発動できない。",
            engine.whyCannotActivate(other, me)
        )
    }

    @Test
    fun `a restriction can spare the named category`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        // 「VALIS」以外のモンスターは特殊召喚できない。
        val card = restricting(
            "縛る魔法",
            RestrictionKind.SPECIAL_SUMMON,
            PlayerRef.SELF,
            filters = listOf(CategoryFilter(valis)),
            except = true
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue(engine.summonAllowed(monster("VALIS・剣", valis), me, SummonKind.SPECIAL))
        assertFalse(engine.summonAllowed(monster("よその子"), me, SummonKind.SPECIAL))
    }

    @Test
    fun `a this-turn restriction is gone next turn`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        state.players[1].deck.add(monster("相手の山札"))
        me.deck.add(monster("山札"))

        val card = restricting("今だけ封じ", RestrictionKind.SPECIAL_SUMMON, PlayerRef.SELF)
        me.hand.add(card)
        engine.activateCard(card, me)
        assertFalse(engine.summonAllowed(monster("誰か"), me, SummonKind.SPECIAL))

        engine.endTurnImmediately()
        assertTrue("次のターンには消える", engine.summonAllowed(monster("誰か"), me, SummonKind.SPECIAL))
    }

    @Test
    fun `a next-turn restriction survives one turn change`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        state.players[1].deck.add(monster("相手の山札"))
        me.deck.add(monster("山札"))

        val card = restricting(
            "次のターンまで封じ",
            RestrictionKind.SPECIAL_SUMMON,
            PlayerRef.SELF,
            duration = RestrictionDuration.NEXT_TURN
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        engine.endTurnImmediately()
        assertFalse("次のターンはまだ掛かっている", engine.summonAllowed(monster("誰か"), me, SummonKind.SPECIAL))
        engine.endTurnImmediately()
        assertTrue("その次には消える", engine.summonAllowed(monster("誰か"), me, SummonKind.SPECIAL))
    }

    @Test
    fun `a continuous restriction applies while the card stays`() {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val lock = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "攻撃封じの結界", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                RestrictAction(
                                    who = PlayerRef.OPPONENT,
                                    kind = RestrictionKind.ATTACK
                                )
                            )
                        )
                    )
                )
            )
        )
        me.spellTrapZones[0] = lock

        val attacker = monster("殴る者")
        opponent.monsterZones[0] = attacker
        attacker.summonedOnTurn = state.turn - 1
        state.turnPlayerIndex = 1
        state.phase = Phase.BATTLE

        assertFalse("永続の間は攻撃できない", engine.canAttack(attacker))

        me.spellTrapZones[0] = null
        assertTrue("カードが離れれば戻る", engine.canAttack(attacker))
    }

    // -- 【制限】として掛ける ----------------------------------------------

    @Test
    fun `a play lock survives a negated activation`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val counter = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "無効にする罠", kind = CardKind.TRAP,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(EffectClause(actions = listOf(NegateAction)))
                )
            )
        )
        opponent.spellTrapZones[0] = counter
        counter.faceDown = true
        counter.setOnTurn = state.turn - 1

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "魔法を縛る魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    playLocks = listOf(
                        PlayLock(
                            who = PlayerRef.OPPONENT,
                            kind = RestrictionKind.ACTIVATE_SPELL,
                            except = false
                        )
                    ),
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 500)))
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("効果は無効になった", 8000, me.life)
        assertFalse(
            "それでも制限は掛かる",
            engine.allowed(opponent, RestrictionKind.ACTIVATE_SPELL, null)
        )
    }

    @Test
    fun `an old summon lock is migrated to a play lock`() {
        val old = CardDef(
            id = "old", name = "旧データ", kind = CardKind.SPELL,
            effect = EffectText(
                summonLocks = listOf(
                    SummonLock(
                        who = PlayerRef.SELF,
                        summon = SummonKind.SPECIAL,
                        filters = listOf(CategoryFilter(valis)),
                        except = true
                    )
                ),
                clauses = listOf(
                    EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))
                )
            )
        )
        val migrated = LegacyMigration.migrate(old).effect!!
        assertTrue(migrated.summonLocks.isEmpty())
        assertEquals(1, migrated.playLocks.size)
        assertEquals(RestrictionKind.SPECIAL_SUMMON, migrated.playLocks.first().kind)
    }

    // -- 文 ----------------------------------------------------------------

    @Test
    fun `restrictions are written plainly`() {
        assertEquals(
            "このターンの間、相手のモンスターは直接攻撃できない",
            EffectTextRenderer.actionToText(
                RestrictAction(PlayerRef.OPPONENT, RestrictionKind.DIRECT_ATTACK), master
            )
        )
        assertEquals(
            "このカードを発動するターン、相手は魔法カードを発動できない",
            EffectTextRenderer.playLockToText(
                PlayLock(PlayerRef.OPPONENT, RestrictionKind.ACTIVATE_SPELL, except = false),
                master
            )
        )
        assertEquals(
            "このカードを発動するターン、自分は「VALIS」モンスター以外のモンスターを特殊召喚できない",
            EffectTextRenderer.playLockToText(
                PlayLock(
                    PlayerRef.SELF,
                    RestrictionKind.SPECIAL_SUMMON,
                    listOf(CategoryFilter(valis)),
                    except = true
                ),
                master
            )
        )
    }
}
