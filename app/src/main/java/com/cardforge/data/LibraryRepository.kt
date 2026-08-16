package com.cardforge.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<Library>(file.readText())
            else DefaultData.seed()
        }.getOrElse { DefaultData.seed() }

        library = loaded.copy(cards = loaded.cards.map(LegacyMigration::migrate))
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
            MasterKind.COUNTER -> master.copy(counters = master.counters.filterNot { it.id == entryId })
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

                // カウンターの種類はカード本体には載らないので、そのまま。
                MasterKind.COUNTER -> card
            }
        }
        lib.copy(master = newMaster, cards = newCards)
    }

    // -- イラスト -----------------------------------------------------------

    /**
     * 端末から選んだ画像をアプリ内部に取り込み、そのパスを返す。
     *
     * 端末の写真をそのまま抱えると容量も書き出しファイルも重くなるので、
     * 長辺 [MAX_IMAGE_SIZE]px の JPEG に縮小して保存する。
     * 取り込んでおけば、元画像が消えても URI 権限が切れてもイラストは残る。
     */
    fun importImage(uri: Uri): String? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null

        val scaled = downscale(bitmap)
        val path = writeBitmap(scaled)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        path
    }.getOrNull()

    /**
     * 内部ストレージへ保存する。
     *
     * 透過のある画像を JPEG にすると透明部分が黒く潰れてしまうので、
     * その場合は PNG で保存してアルファを残す。
     */
    private fun writeBitmap(bitmap: Bitmap): String {
        val transparent = bitmap.hasAlpha()
        val target = File(imageDir, "${newId()}." + if (transparent) "png" else "jpg")
        target.outputStream().use { output ->
            if (transparent) bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        }
        return target.absolutePath
    }

    /**
     * [sourcePath] の画像を、正規化された矩形（0.0〜1.0）で切り抜いて保存する。
     * 元のファイルは残さない。
     */
    fun cropImage(
        sourcePath: String,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ): String? = runCatching {
        val source = BitmapFactory.decodeFile(sourcePath) ?: return null

        val x = (left * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (top * source.height).toInt().coerceIn(0, source.height - 1)
        val w = (width * source.width).toInt().coerceIn(1, source.width - x)
        val h = (height * source.height).toInt().coerceIn(1, source.height - y)

        val cropped = Bitmap.createBitmap(source, x, y, w, h)
        val scaled = downscale(cropped)
        val path = writeBitmap(scaled)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== source) cropped.recycle()
        source.recycle()
        runCatching { File(sourcePath).delete() }
        path
    }.getOrNull()

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_IMAGE_SIZE) return source
        val ratio = MAX_IMAGE_SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

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

    // -- 書き出しと取り込み -------------------------------------------------

    /**
     * [cards] と [decks] を、参照しているマスターデータとイラストごと
     * 1つの JSON にまとめる。
     */
    fun buildExport(cards: List<CardDef>, decks: List<Deck>): String {
        val master = library.master
        val referenced = cards.flatMap { IdRemapper.referencedIds(it) }.toSet()

        val payload = CardExchange(
            master = MasterData(
                attributes = master.attributes.filter { it.id in referenced },
                races = master.races.filter { it.id in referenced },
                categories = master.categories.filter { it.id in referenced },
                counters = master.counters.filter { it.id in referenced }
            ),
            cards = cards,
            decks = decks,
            images = cards.mapNotNull { card ->
                val path = card.imagePath ?: return@mapNotNull null
                val bytes = runCatching { File(path).readBytes() }.getOrNull()
                    ?: return@mapNotNull null
                path to Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.toMap()
        )
        return json.encodeToString(CardExchange.serializer(), payload)
    }

    fun exportAll(): String = buildExport(library.cards, library.decks)

    /** デッキと、そのデッキが使っているカードだけを書き出す。 */
    fun exportDeck(deck: Deck): String {
        val used = deck.cardIds.toSet()
        return buildExport(library.cards.filter { it.id in used }, listOf(deck))
    }

    /**
     * 書き出しファイルを取り込む。
     *
     * 属性・種族・カテゴリは同じ名前のものがあれば使い回し、無ければ追加する。
     * カードは ID が一致するものを上書きするので、同じファイルを2回取り込んでも
     * 増えていかない。
     */
    /** 書き出しファイルを読むだけ。取り込む前に中身を見せるために使う。 */
    fun parseExchange(text: String): Result<CardExchange> = runCatching {
        json.decodeFromString(CardExchange.serializer(), text)
    }

    fun importExchange(text: String): Result<ImportSummary> =
        parseExchange(text).mapCatching { importSelection(it, null, null) }

    /**
     * [payload] のうち、[cardIds] と [deckIds] に含まれるものだけを取り込む。
     * null なら全て取り込む。
     */
    fun importSelection(
        source: CardExchange,
        cardIds: Set<String>?,
        deckIds: Set<String>?
    ): ImportSummary {
        val payload = source.copy(
            cards = source.cards.filter { cardIds == null || it.id in cardIds },
            decks = source.decks.filter { deckIds == null || it.id in deckIds }
        )
        val current = library
        val master = current.master

        // --- マスターデータを突き合わせる ---
        val mapping = mutableMapOf<String, String>()
        var addedMaster = 0

        fun merge(
            incoming: List<NamedEntry>,
            existing: List<NamedEntry>
        ): List<NamedEntry> {
            val result = existing.toMutableList()
            incoming.forEach { entry ->
                val match = result.firstOrNull { it.name == entry.name }
                if (match != null) {
                    mapping[entry.id] = match.id
                } else {
                    result += entry
                    addedMaster++
                }
            }
            return result
        }

        val newMaster = MasterData(
            attributes = merge(payload.master.attributes, master.attributes),
            races = merge(payload.master.races, master.races),
            categories = merge(payload.master.categories, master.categories),
            counters = merge(payload.master.counters, master.counters)
        )

        // --- イラストを内部ストレージに戻す ---
        val imagePaths = mutableMapOf<String, String>()
        payload.images.forEach { (originalPath, encoded) ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                val target = File(imageDir, "${newId()}.jpg")
                target.writeBytes(bytes)
                imagePaths[originalPath] = target.absolutePath
            }
        }

        // --- カードとデッキ ---
        var added = 0
        var updated = 0
        val cards = current.cards.toMutableList()
        payload.cards.forEach { incoming ->
            val remapped = IdRemapper.remapCard(incoming, mapping).copy(
                imagePath = incoming.imagePath?.let { imagePaths[it] }
            )
            val index = cards.indexOfFirst { it.id == remapped.id }
            if (index >= 0) {
                cards[index] = remapped
                updated++
            } else {
                cards += remapped
                added++
            }
        }

        var addedDecks = 0
        val decks = current.decks.toMutableList()
        payload.decks.forEach { incoming ->
            // 取り込めなかったカードは取り除いておく。
            val known = cards.map { it.id }.toSet()
            val cleaned = incoming.copy(cardIds = incoming.cardIds.filter { it in known })
            val index = decks.indexOfFirst { it.id == cleaned.id }
            if (index >= 0) decks[index] = cleaned else {
                decks += cleaned
                addedDecks++
            }
        }

        library = current.copy(master = newMaster, cards = cards, decks = decks)
        persist()

        return ImportSummary(added, updated, addedDecks, addedMaster)
    }

    private companion object {
        const val MAX_IMAGE_SIZE = 1024
    }
}

enum class MasterKind(val label: String) {
    ATTRIBUTE("属性"),
    RACE("種族"),
    CATEGORY("カテゴリ"),
    COUNTER("カウンター")
}
