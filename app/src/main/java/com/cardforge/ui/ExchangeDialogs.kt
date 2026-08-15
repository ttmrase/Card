package com.cardforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.data.CardExchange
import com.cardforge.model.CardDef
import com.cardforge.model.Deck
import com.cardforge.model.MasterData
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Gold

/** 書き出し／取り込みで選んだ範囲。 */
data class ExchangeSelection(val cardIds: Set<String>, val deckIds: Set<String>)

/**
 * カードとデッキを選ぶダイアログ。書き出しにも取り込みにも使う。
 * デッキを選ぶと、そのデッキが使っているカードも自動で選ばれる。
 */
@Composable
fun ExchangeSelectionDialog(
    title: String,
    confirmLabel: String,
    cards: List<CardDef>,
    decks: List<Deck>,
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (ExchangeSelection) -> Unit
) {
    val selectedCards = remember { mutableStateListOf<String>().apply { addAll(cards.map { it.id }) } }
    val selectedDecks = remember { mutableStateListOf<String>().apply { addAll(decks.map { it.id }) } }
    var preview by remember { mutableStateOf<CardDef?>(null) }

    fun toggleDeck(deck: Deck) {
        if (deck.id in selectedDecks) {
            selectedDecks.remove(deck.id)
        } else {
            selectedDecks.add(deck.id)
            // デッキに入っているカードは一緒に選んでおく。
            deck.cardIds.distinct().forEach { if (it !in selectedCards) selectedCards.add(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "カード ${selectedCards.size} / ${cards.size}　" +
                        "デッキ ${selectedDecks.size} / ${decks.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Gold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("すべて選ぶ") {
                        selectedCards.clear(); selectedCards.addAll(cards.map { it.id })
                        selectedDecks.clear(); selectedDecks.addAll(decks.map { it.id })
                    }
                    Chip("すべて外す") {
                        selectedCards.clear()
                        selectedDecks.clear()
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (decks.isNotEmpty()) {
                        item {
                            Text(
                                "デッキ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(decks, key = { "deck-" + it.id }) { deck ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { toggleDeck(deck) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = deck.id in selectedDecks,
                                    onCheckedChange = { toggleDeck(deck) }
                                )
                                Text("${deck.name}（${deck.size}枚）", Modifier.weight(1f))
                            }
                        }
                    }

                    item {
                        Text(
                            "カード（長押しで効果を確認）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(cards, key = { "card-" + it.id }) { card ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .tapOrHold(
                                    onClick = {
                                        if (card.id in selectedCards) selectedCards.remove(card.id)
                                        else selectedCards.add(card.id)
                                    },
                                    onLongClick = { preview = card }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = card.id in selectedCards,
                                onCheckedChange = {
                                    if (card.id in selectedCards) selectedCards.remove(card.id)
                                    else selectedCards.add(card.id)
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(card.name)
                                Text(
                                    EffectTextRenderer.summary(card, master),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ExchangeSelection(selectedCards.toSet(), selectedDecks.toSet())
                    )
                },
                enabled = selectedCards.isNotEmpty() || selectedDecks.isNotEmpty()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    preview?.let { card ->
        CardPreviewDialog(card = card, master = master, onDismiss = { preview = null })
    }
}

/** 取り込むファイルの中身から選ばせる。 */
@Composable
fun ImportSelectionDialog(
    payload: CardExchange,
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (ExchangeSelection) -> Unit
) {
    ExchangeSelectionDialog(
        title = "取り込む内容を選択",
        confirmLabel = "取り込む",
        cards = payload.cards,
        decks = payload.decks,
        // ファイル内の属性・種族・カテゴリで表示する。
        master = payload.master,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}
