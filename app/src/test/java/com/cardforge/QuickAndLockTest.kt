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

/** 「任意」の確認に対して、決められた答えを返すプレイヤー。 */
private class Answering(private val answer: Boolean) : Interaction {
    val asked = mutableListOf<String>()

    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String): Boolean {
        asked += prompt
        return answer
    }

    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 誘発即時効果、処理ごとの任意／強制、【制限】としての召喚制限。 */
class QuickAndLockTest {

    private val valis = "cat-valis"
    private val master = MasterData(categories = listOf(NamedEntry(valis, "VALIS")))

    private fun game(
        interaction: Interaction = Answering(true),
        turnPlayer: Int = 0
    ): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, interaction)
    }

    private fun monster(name: String, category: String? = null) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            categoryIds = listOfNotNull(category)
        )
    )

    // -- 誘発即時効果 -----------------------------------------------------

    /** フィールドのモンスターが持つ、相手モンスターを破壊する効果。 */
    private fun disruptor(quick: Boolean) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "割り込む者", kind = CardKind.MONSTER,
            level = 4, atk = 1500, def = 1000,
            effect = EffectText(
                locations = listOf(ActivationLocation.FIELD),
                clauses = listOf(
                    EffectClause(
                        quick = quick,
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
    fun `a quick effect on the field can be used on the opponent's turn`() {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val quick = disruptor(quick = true)
        me.monsterZones[0] = quick
        opponent.monsterZones[0] = monster("相手の壁")

        assertTrue(
            "相手ターンでも発動候補に出る",
            engine.activatableCards(me).any { it === quick }
        )
        assertTrue(
            "割り込みの候補にも出る",
            engine.respondableCards(me).any { it === quick }
        )
    }

    @Test
    fun `a plain field effect stays on my own turn`() {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val slow = disruptor(quick = false)
        me.monsterZones[0] = slow
        opponent.monsterZones[0] = monster("相手の壁")

        assertFalse(engine.activatableCards(me).any { it === slow })
        assertFalse(engine.respondableCards(me).any { it === slow })
    }

    @Test
    fun `a quick effect works outside the main phase on my own turn`() {
        val (state, engine) = game(turnPlayer = 0)
        val me = state.players[0]
        val opponent = state.players[1]

        val quick = disruptor(quick = true)
        me.monsterZones[0] = quick
        opponent.monsterZones[0] = monster("相手の壁")

        state.phase = Phase.BATTLE
        assertTrue(engine.activatableCards(me).any { it === quick })

        val slow = disruptor(quick = false)
        me.monsterZones[1] = slow
        assertFalse("普通の起動効果はメインフェイズのみ", engine.activatableCards(me).any { it === slow })
    }

    @Test
    fun `a hand effect without the mark cannot respond`() {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val handCard = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "手札の起動役", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            locations = listOf(ActivationLocation.HAND),
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
        me.hand.add(handCard)
        opponent.monsterZones[0] = monster("相手の壁")

        assertFalse(
            "【誘発即時】を付けていないので相手ターンには撃てない",
            engine.activatableCards(me).any { it === handCard }
        )
        assertFalse(engine.respondableCards(me).any { it === handCard })
    }

    @Test
    fun `the quick mark shows in the card text`() {
        val text = EffectTextRenderer.render(disruptor(quick = true).card, master)
        assertTrue(text, text.contains("【誘発即時】"))
    }

    // -- 処理ごとの任意／強制 ---------------------------------------------

    private fun twoSteps() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "2段構え", kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        // 1つ目は強制、2つ目だけ任意。
                        optionalSteps = listOf(1),
                        actions = listOf(
                            RecoverAction(PlayerRef.SELF, 500),
                            DrawAction(PlayerRef.SELF, 1)
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `an optional step is asked and can be declined`() = runBlocking {
        val declining = Answering(false)
        val (state, engine) = game(interaction = declining)
        val me = state.players[0]
        repeat(3) { me.deck.add(monster("山札$it")) }

        val card = twoSteps()
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("強制の処理は行われる", 8500, me.life)
        assertEquals("断ったのでドローしない", 0, me.hand.size)
        assertTrue(declining.asked.toString(), declining.asked.any { it.contains("（任意）") })
    }

    @Test
    fun `an optional step runs when accepted`() = runBlocking {
        val (state, engine) = game(interaction = Answering(true))
        val me = state.players[0]
        repeat(3) { me.deck.add(monster("山札$it")) }

        val card = twoSteps()
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals(8500, me.life)
        assertEquals("受けたのでドローする", 1, me.hand.size)
    }

    @Test
    fun `an optional step does not block activation when it cannot resolve`() {
        val (state, engine) = game()
        val me = state.players[0]

        val card = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "任意の破壊", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            optionalSteps = listOf(1),
                            actions = listOf(
                                RecoverAction(PlayerRef.SELF, 100),
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
        me.hand.add(card)
        // 相手の場は空なので、任意の破壊は対象が無い。

        assertTrue(
            "任意の処理は発動可否に数えない：" + engine.whyCannotActivate(card, me),
            engine.activatableCards(me).any { it === card }
        )
    }

    @Test
    fun `the optional step reads as can-do in the text`() {
        val text = EffectTextRenderer.render(twoSteps().card, master)
        assertTrue(text, text.contains("ドローすることができる"))
        assertTrue(text, text.contains("回復する。"))
    }

    // -- 【制限】としての召喚制限 ------------------------------------------

    private fun lockingSpell() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "VALIS・号令", kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                summonLocks = listOf(
                    SummonLock(
                        who = PlayerRef.SELF,
                        summon = SummonKind.SPECIAL,
                        filters = listOf(CategoryFilter(valis)),
                        except = true
                    )
                ),
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100)))
                )
            )
        )
    )

    @Test
    fun `the lock stays even when the effect is negated`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        // 相手は「発動を無効にし破壊する」罠を伏せている。
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

        val card = lockingSpell()
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("効果は無効になったので回復しない", 8000, me.life)
        assertFalse(
            "それでも召喚制限は掛かったまま",
            engine.summonAllowed(monster("よその子"), me, SummonKind.SPECIAL)
        )
        assertTrue(engine.summonAllowed(monster("VALIS", valis), me, SummonKind.SPECIAL))
    }

    @Test
    fun `the lock is written in the limit section`() {
        val text = EffectTextRenderer.render(lockingSpell().card, master)
        val line = text.lines().first { it.startsWith("【制限】") }
        assertEquals(
            "【制限】このカードを発動するターン、自分は「VALIS」モンスター以外のモンスターを特殊召喚できない",
            line
        )
    }

    @Test
    fun `an old restriction written as an effect is moved to the limit section`() {
        val old = CardDef(
            id = "old", name = "旧データ", kind = CardKind.SPELL,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        mode = ActivationMode.ON_ACTIVATION,
                        actions = listOf(
                            RestrictSummonAction(
                                who = PlayerRef.SELF,
                                summon = SummonKind.SPECIAL,
                                filters = listOf(CategoryFilter(valis)),
                                except = true
                            )
                        )
                    ),
                    EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))
                )
            )
        )

        val migrated = LegacyMigration.migrate(old)
        val effect = migrated.effect!!
        assertEquals("【制限】に移る", 1, effect.summonLocks.size)
        assertEquals("制限だけの効果は消える", 1, effect.clauses.size)
        assertTrue(
            effect.clauses.none { clause -> clause.actions.any { it is RestrictSummonAction } }
        )
    }
}
