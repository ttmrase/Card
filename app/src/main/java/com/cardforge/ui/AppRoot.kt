package com.cardforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.cardforge.data.LibraryRepository

/** アプリ内の画面。ナビゲーションは単純なスタックで管理する。 */
sealed interface Screen {
    data object Home : Screen
    data object CardList : Screen
    data class CardEdit(val cardId: String?) : Screen
    data object MasterData : Screen
    data object DeckList : Screen
    data class DeckEdit(val deckId: String?) : Screen
    data object DuelSetup : Screen
    data class Duel(val config: DuelConfig) : Screen
}

data class DuelConfig(
    val deckAId: String,
    val deckBId: String,
    val playerAName: String,
    val playerBName: String,
    val versusAi: Boolean
)

@Composable
fun AppRoot(repository: LibraryRepository) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    fun push(screen: Screen) = stack.add(screen)
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val current = stack.last()) {
        Screen.Home -> HomeScreen(
            onOpenCards = { push(Screen.CardList) },
            onOpenDecks = { push(Screen.DeckList) },
            onOpenMaster = { push(Screen.MasterData) },
            onStartDuel = { push(Screen.DuelSetup) }
        )

        Screen.CardList -> CardListScreen(
            repository = repository,
            onBack = ::pop,
            onEdit = { cardId -> push(Screen.CardEdit(cardId)) },
            onCreate = { push(Screen.CardEdit(null)) }
        )

        is Screen.CardEdit -> CardEditScreen(
            repository = repository,
            cardId = current.cardId,
            onBack = ::pop
        )

        Screen.MasterData -> MasterDataScreen(repository = repository, onBack = ::pop)

        Screen.DeckList -> DeckListScreen(
            repository = repository,
            onBack = ::pop,
            onEdit = { deckId -> push(Screen.DeckEdit(deckId)) },
            onCreate = { push(Screen.DeckEdit(null)) }
        )

        is Screen.DeckEdit -> DeckEditScreen(
            repository = repository,
            deckId = current.deckId,
            onBack = ::pop
        )

        Screen.DuelSetup -> DuelSetupScreen(
            repository = repository,
            onBack = ::pop,
            onStart = { config -> push(Screen.Duel(config)) }
        )

        is Screen.Duel -> DuelScreen(
            repository = repository,
            config = current.config,
            onExit = ::pop
        )
    }
}
