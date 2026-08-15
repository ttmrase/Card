package com.cardforge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cardforge.game.*
import com.cardforge.model.Deck
import com.cardforge.model.Library
import kotlinx.coroutines.CompletableDeferred

/** エンジンが待っているプレイヤーへの問い合わせ。 */
sealed interface DuelPrompt {
    val playerIndex: Int
    val message: String

    data class CardSelection(
        override val playerIndex: Int,
        override val message: String,
        val candidates: List<CardInstance>,
        val min: Int,
        val max: Int,
        val deferred: CompletableDeferred<List<CardInstance>>
    ) : DuelPrompt

    data class Confirm(
        override val playerIndex: Int,
        override val message: String,
        val deferred: CompletableDeferred<Boolean>
    ) : DuelPrompt

    data class Options(
        override val playerIndex: Int,
        override val message: String,
        val options: List<String>,
        val deferred: CompletableDeferred<Int>
    ) : DuelPrompt
}

/**
 * デュエル画面の状態をまとめて持つ。
 *
 * エンジンは suspend 関数でプレイヤーの入力を待つので、UI 側は
 * [pendingPrompt] にダイアログを出し、結果を [CompletableDeferred] に流し込む。
 */
class DuelController(
    library: Library,
    val config: DuelConfig
) {

    val state: GameState

    var pendingPrompt by mutableStateOf<DuelPrompt?>(null)
        private set

    var toast by mutableStateOf<String?>(null)

    /** AI が思考中かどうか。UI の操作を止めるために使う。 */
    var aiThinking by mutableStateOf(false)
        private set

    val humanIndex: Int = 0
    val aiIndex: Int? = if (config.versusAi) 1 else null

    private val engineRef: GameEngine
    private val aiController: AiController?

    val engine: GameEngine get() = engineRef

    init {
        val deckA = library.decks.first { it.id == config.deckAId }
        val deckB = library.decks.first { it.id == config.deckBId }
        state = GameSetup.build(
            library = library,
            deckA = deckA,
            nameA = config.playerAName,
            deckB = deckB,
            nameB = config.playerBName
        )

        val ui = UiInteraction()
        val interaction = if (aiIndex != null) {
            RoutingInteraction(ui, AiInteraction(state, aiIndex), aiIndex)
        } else {
            ui
        }
        engineRef = GameEngine(state, interaction)
        // 1手ごとに少し間を置いて、相手の動きを追えるようにする。
        aiController = aiIndex?.let { AiController(engineRef, it, AI_PAUSE_MILLIS) }
    }

    // -- プロンプトの解決 ---------------------------------------------------

    fun resolveSelection(selected: List<CardInstance>) {
        val prompt = pendingPrompt as? DuelPrompt.CardSelection ?: return
        pendingPrompt = null
        prompt.deferred.complete(selected)
    }

    fun resolveConfirm(answer: Boolean) {
        val prompt = pendingPrompt as? DuelPrompt.Confirm ?: return
        pendingPrompt = null
        prompt.deferred.complete(answer)
    }

    fun resolveOption(index: Int) {
        val prompt = pendingPrompt as? DuelPrompt.Options ?: return
        pendingPrompt = null
        prompt.deferred.complete(index)
    }

    suspend fun runAiTurn() {
        val ai = aiController ?: return
        aiThinking = true
        try {
            ai.playTurn()
        } finally {
            aiThinking = false
        }
    }

    private companion object {
        /** AI の1手ごとの間（ミリ秒）。 */
        const val AI_PAUSE_MILLIS = 700L
    }

    private inner class UiInteraction : Interaction {

        override suspend fun chooseCards(
            playerIndex: Int,
            prompt: String,
            candidates: List<CardInstance>,
            min: Int,
            max: Int
        ): List<CardInstance> {
            if (candidates.isEmpty()) return emptyList()
            val deferred = CompletableDeferred<List<CardInstance>>()
            pendingPrompt = DuelPrompt.CardSelection(
                playerIndex, prompt, candidates, min, max, deferred
            )
            return deferred.await()
        }

        override suspend fun chooseZone(
            playerIndex: Int,
            prompt: String,
            freeZones: List<Int>
        ): Int? = freeZones.firstOrNull()

        override suspend fun confirm(playerIndex: Int, prompt: String): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            pendingPrompt = DuelPrompt.Confirm(playerIndex, prompt, deferred)
            return deferred.await()
        }

        override suspend fun chooseOption(
            playerIndex: Int,
            prompt: String,
            options: List<String>
        ): Int {
            if (options.size <= 1) return 0
            val deferred = CompletableDeferred<Int>()
            pendingPrompt = DuelPrompt.Options(playerIndex, prompt, options, deferred)
            return deferred.await()
        }

        override suspend fun notify(playerIndex: Int, message: String) {
            toast = message
        }
    }
}

/** プレイヤーごとに、UI と AI のどちらに問い合わせるかを振り分ける。 */
private class RoutingInteraction(
    private val ui: Interaction,
    private val ai: Interaction,
    private val aiIndex: Int
) : Interaction {

    private fun of(playerIndex: Int): Interaction =
        if (playerIndex == aiIndex) ai else ui

    override suspend fun chooseCards(
        playerIndex: Int,
        prompt: String,
        candidates: List<CardInstance>,
        min: Int,
        max: Int
    ): List<CardInstance> = of(playerIndex).chooseCards(playerIndex, prompt, candidates, min, max)

    override suspend fun chooseZone(
        playerIndex: Int,
        prompt: String,
        freeZones: List<Int>
    ): Int? = of(playerIndex).chooseZone(playerIndex, prompt, freeZones)

    override suspend fun confirm(playerIndex: Int, prompt: String): Boolean =
        of(playerIndex).confirm(playerIndex, prompt)

    override suspend fun chooseOption(
        playerIndex: Int,
        prompt: String,
        options: List<String>
    ): Int = of(playerIndex).chooseOption(playerIndex, prompt, options)

    override suspend fun notify(playerIndex: Int, message: String) =
        of(playerIndex).notify(playerIndex, message)
}
