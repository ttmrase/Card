package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class HandYes : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 何も発動しないプレイヤー。応答の窓を開いたことだけを記録する。 */
private class NeverRespond : Interaction {
    val prompts = mutableListOf<Pair<String, List<String>>>()

    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> {
        if (min == 0) {
            prompts += prompt to candidates.map { it.card.name }
            return emptyList()
        }
        return candidates.take(min.coerceAtMost(maxOf(max, 1)))
    }

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = false
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 手札で発動する効果と、相手ターンの割り込みのテスト。 */
class HandActivationTest {

    private val valis = "cat-valis"

    private fun game(
        interaction: Interaction = HandYes(),
        turnPlayer: Int = 0
    ): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, interaction)
    }

    private fun valisMonster(name: String) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            categoryIds = listOf(valis)
        )
    )

    /** 画面の設定そのまま：手札で発動し、自身を特殊召喚するモンスター。 */
    private fun selfSummoner() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "VALIS・セルフ", kind = CardKind.MONSTER,
            level = 6, atk = 2400, def = 1200,
            categoryIds = listOf(valis),
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        locations = listOf(ActivationLocation.HAND),
                        conditions = listOf(SelfZoneCondition(listOf(ZoneType.HAND))),
                        costs = listOf(
                            MoveCost(
                                CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.GRAVEYARD,
                                    filters = listOf(
                                        CategoryFilter(valis),
                                        KindFilter(CardKind.MONSTER)
                                    ),
                                    count = 1
                                ),
                                MoveDestination.DECK_BOTTOM
                            )
                        ),
                        mode = ActivationMode.OPTIONAL,
                        actions = listOf(
                            SpecialSummonAction(
                                CardScope(selfOnly = true),
                                positionChoices = listOf(Position.ATTACK, Position.DEFENSE)
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `a monster in hand can activate an effect that summons itself`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = selfSummoner()
        val fodder = valisMonster("VALIS・墓地")
        me.hand.add(card)
        me.graveyard.add(fodder)

        assertTrue(
            "手札のモンスターが発動候補に挙がる：" + engine.whyCannotActivate(card, me),
            engine.activatableCards(me).any { it === card }
        )

        assertTrue(engine.activateCard(card, me))
        assertTrue("自身が特殊召喚される", me.monsters.any { it === card })
        assertTrue("コストのカードがデッキの一番下に戻る", me.deck.lastOrNull() === fodder)
    }

    @Test
    fun `without the cost it cannot be activated from hand`() {
        val (state, engine) = game()
        val me = state.players[0]

        val card = selfSummoner()
        me.hand.add(card)
        // 墓地に「VALIS」モンスターがいないのでコストが払えない。

        assertFalse(engine.activatableCards(me).any { it === card })
        assertEquals("【コスト】を支払えない。", engine.whyCannotActivate(card, me))
    }

    /** 手札から発動して相手モンスターを破壊する、いわゆる手札誘発。 */
    private fun handTrap() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "手札の妨害者", kind = CardKind.MONSTER,
            level = 2, atk = 500, def = 500,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        locations = listOf(ActivationLocation.HAND),
                        costs = listOf(DiscardSelfCost()),
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

    /** フィールドで発動する起動効果しか持たないモンスター。 */
    private fun fieldIgnition() = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "場の起動役", kind = CardKind.MONSTER,
            level = 4, atk = 1500, def = 1000,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 500)))
                )
            )
        )
    )

    @Test
    fun `a hand effect can respond on the opponent's turn`() {
        // 相手のターン。自分は手札誘発を持っている。
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val trap = handTrap()
        me.hand.add(trap)
        opponent.monsterZones[0] = valisMonster("相手の壁")

        assertTrue(
            "相手ターンでも割り込める",
            engine.respondableCards(me).any { it === trap }
        )
        assertTrue(
            "誘発即時なので、相手ターンでも普通の発動候補に出る",
            engine.activatableCards(me).any { it === trap }
        )
    }

    @Test
    fun `a field ignition effect cannot respond`() {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]

        val monster = fieldIgnition()
        me.monsterZones[0] = monster

        assertFalse(
            "【場所】フィールドの起動効果は割り込めない",
            engine.respondableCards(me).any { it === monster }
        )
    }

    @Test
    fun `the opponent is offered a response when a card is activated`() = runBlocking {
        val watcher = NeverRespond()
        val (state, engine) = game(interaction = watcher, turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        me.hand.add(handTrap())
        opponent.monsterZones[0] = valisMonster("相手の壁")

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "ただの魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100)))
                    )
                )
            )
        )
        opponent.hand.add(spell)
        engine.activateCard(spell, opponent)

        assertTrue(
            "手札誘発が応答の候補に出る：" + watcher.prompts,
            watcher.prompts.any { (_, names) -> names.contains("手札の妨害者") }
        )
    }

    @Test
    fun `sections and the effect body are separated in the text`() {
        val master = MasterData(
            categories = listOf(NamedEntry(valis, "VALIS"))
        )
        val text = EffectTextRenderer.render(selfSummoner().card, master)
        val line = text.lines().first { it.startsWith("①") }

        assertTrue(line, line.contains(EffectTextRenderer.SECTION_SEPARATOR))
        assertTrue(line, line.contains(EffectTextRenderer.EFFECT_ARROW))
        // 区切りより後ろが効果そのもの。
        val body = line.substringAfter(EffectTextRenderer.EFFECT_ARROW)
        assertTrue(body, body.startsWith("このカードを自分のモンスターゾーンに"))
        assertFalse(body, body.contains("【"))
    }
}
