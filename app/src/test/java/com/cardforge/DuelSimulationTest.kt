package com.cardforge

import com.cardforge.data.DefaultData
import com.cardforge.game.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 同士でデュエルを最後まで回し、クラッシュや盤面の破綻が起きないことを確認する。
 * 効果の解決経路をまとめて踏むので、回帰テストとして働く。
 */
class DuelSimulationTest {

    @Test
    fun `ai duels always reach a conclusion without breaking the board`() {
        val library = DefaultData.seed()
        val deck = library.decks.first()
        var concluded = 0
        val games = 20

        repeat(games) { game ->
            val state = GameSetup.build(library, deck, "A", deck, "B")
            val engine = GameEngine(state, bothSidesAi(state))
            val controllers = listOf(AiController(engine, 0), AiController(engine, 1))

            runBlocking {
                var guard = 0
                while (!state.finished && guard++ < 200) {
                    val before = state.turnPlayerIndex
                    controllers[before].playTurn()

                    state.players.forEach { player ->
                        assertTrue(
                            "life exceeded the starting total in game $game",
                            player.life <= 8000
                        )
                        assertTrue(
                            "monster zone count changed in game $game",
                            player.monsterZones.size == 5
                        )
                        assertTrue(
                            "spell/trap zone count changed in game $game",
                            player.spellTrapZones.size == 5
                        )
                    }

                    assertTrue(
                        "turn did not advance in game $game (turn ${state.turn})",
                        state.finished || state.turnPlayerIndex != before
                    )
                }
            }
            if (state.finished) concluded++
        }

        assertTrue("only $concluded of $games duels concluded", concluded == games)
    }

    private fun bothSidesAi(state: GameState): Interaction = object : Interaction {
        private val sides = listOf(AiInteraction(state, 0), AiInteraction(state, 1))

        override suspend fun chooseCards(
            playerIndex: Int,
            prompt: String,
            candidates: List<CardInstance>,
            min: Int,
            max: Int
        ) = sides[playerIndex].chooseCards(playerIndex, prompt, candidates, min, max)

        override suspend fun chooseZone(
            playerIndex: Int,
            prompt: String,
            freeZones: List<Int>
        ) = sides[playerIndex].chooseZone(playerIndex, prompt, freeZones)

        override suspend fun confirm(playerIndex: Int, prompt: String) =
            sides[playerIndex].confirm(playerIndex, prompt)

        override suspend fun chooseOption(
            playerIndex: Int,
            prompt: String,
            options: List<String>
        ) = sides[playerIndex].chooseOption(playerIndex, prompt, options)
    }
}
