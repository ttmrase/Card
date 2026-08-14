package com.cardforge.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cardforge.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * カード・デッキ・マスターデータをアプリ内部ストレージの JSON に永続化する。
 *
 * [library] は Compose の状態なので、更新するとそれを読んでいる画面が
 * 自動的に再描画される。
 */
class LibraryRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val file: File get() = File(context.filesDir, "library.json")
    private val imageDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    var library: Library by mutableStateOf(Library())
        private set

    fun load() {
        library = runCatching {
            if (file.exists()) json.decodeFromString<Library>(file.readText())
            else DefaultData.seed()
        }.getOrElse { DefaultData.seed() }
        // 初回起動時など、まだファイルが無ければ書き出しておく。
        if (!file.exists()) persist()
    }

    private fun persist() {
        val snapshot = library
        scope.launch {
            writeLock.withLock {
                runCatching {
                    val tmp = File(context.filesDir, "library.json.tmp")
                    tmp.writeText(json.encodeToString(snapshot))
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun update(block: (Library) -> Library) {
        library = block(library)
        persist()
    }

    // -- カード -------------------------------------------------------------

    fun upsertCard(card: CardDef) = update { lib ->
        val index = lib.cards.indexOfFirst { it.id == card.id }
        if (index >= 0) lib.copy(cards = lib.cards.toMutableList().also { it[index] = card })
        else lib.copy(cards = lib.cards + card)
    }

    fun deleteCard(cardId: String) = update { lib ->
        // 消したカードはデッキからも取り除く。
        lib.copy(
            cards = lib.cards.filterNot { it.id == cardId },
            decks = lib.decks.map { deck ->
                deck.copy(cardIds = deck.cardIds.filterNot { it == cardId })
            }
        )
    }

    fun duplicateCard(cardId: String): CardDef? {
        val source = library.card(cardId) ?: return null
        val copy = source.copy(id = newId(), name = source.name + "のコピー")
        upsertCard(copy)
        return copy
    }

    // -- デッキ -------------------------------------------------------------

    fun upsertDeck(deck: Deck) = update { lib ->
        val index = lib.decks.indexOfFirst { it.id == deck.id }
        if (index >= 0) lib.copy(decks = lib.decks.toMutableList().also { it[index] = deck })
        else lib.copy(decks = lib.decks + deck)
    }

    fun deleteDeck(deckId: String) = update { lib ->
        lib.copy(decks = lib.decks.filterNot { it.id == deckId })
    }

    // -- マスターデータ -----------------------------------------------------

    fun updateMaster(master: MasterData) = update { it.copy(master = master) }

    /**
     * マスター項目を削除し、それを参照しているカード側の参照も掃除する。
     */
    fun deleteMasterEntry(kind: MasterKind, entryId: String) = update { lib ->
        val master = lib.master
        val newMaster = when (kind) {
            MasterKind.ATTRIBUTE -> master.copy(attributes = master.attributes.filterNot { it.id == entryId })
            MasterKind.RACE -> master.copy(races = master.races.filterNot { it.id == entryId })
            MasterKind.CATEGORY -> master.copy(categories = master.categories.filterNot { it.id == entryId })
        }
        val newCards = lib.cards.map { card ->
            when (kind) {
                MasterKind.ATTRIBUTE ->
                    if (card.attributeId == entryId) card.copy(attributeId = null) else card

                MasterKind.RACE ->
                    if (card.raceId == entryId) card.copy(raceId = null) else card

                MasterKind.CATEGORY ->
                    if (entryId in card.categoryIds)
                        card.copy(categoryIds = card.categoryIds - entryId)
                    else card
            }
        }
        lib.copy(master = newMaster, cards = newCards)
    }

    // -- イラスト -----------------------------------------------------------

    /**
     * 端末から選んだ画像をアプリ内部にコピーし、そのパスを返す。
     * コピーしておくことで、あとから元画像が消えたり URI 権限が失効しても
     * カードのイラストが失われない。
     */
    fun importImage(uri: Uri): String? = runCatching {
        val target = File(imageDir, "${newId()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    /** どのカードからも参照されていないイラストを削除する。 */
    fun pruneUnusedImages() {
        val used = library.cards.mapNotNull { it.imagePath }.toSet()
        scope.launch {
            runCatching {
                imageDir.listFiles()?.forEach { f ->
                    if (f.absolutePath !in used) f.delete()
                }
            }
        }
    }
}

enum class MasterKind(val label: String) {
    ATTRIBUTE("属性"),
    RACE("種族"),
    CATEGORY("カテゴリ")
}
