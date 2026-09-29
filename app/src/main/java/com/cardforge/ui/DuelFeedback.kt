package com.cardforge.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.cardforge.game.BoardSignalKind
import com.cardforge.ui.theme.Accent
import com.cardforge.ui.theme.Boost
import com.cardforge.ui.theme.Danger
import com.cardforge.ui.theme.Gold

/** 出来事ごとの色。短い字幕の色に使う。 */
fun signalColor(kind: BoardSignalKind): Color = when (kind) {
    BoardSignalKind.DESTROYED, BoardSignalKind.DAMAGE, BoardSignalKind.ATTACK -> Danger
    BoardSignalKind.RECOVER -> Boost
    BoardSignalKind.BANISHED, BoardSignalKind.SENT_TO_GRAVEYARD, BoardSignalKind.TURN_START -> Gold
    else -> Accent
}

/** 出来事ごとの音。高さと長さを変えて聞き分けられるようにする。 */
private fun signalTone(kind: BoardSignalKind): Pair<Int, Int> = when (kind) {
    BoardSignalKind.TURN_START -> ToneGenerator.TONE_PROP_ACK to 160
    BoardSignalKind.DESTROYED -> ToneGenerator.TONE_CDMA_ABBR_ALERT to 220
    BoardSignalKind.DAMAGE -> ToneGenerator.TONE_CDMA_LOW_L to 200
    BoardSignalKind.RECOVER -> ToneGenerator.TONE_CDMA_HIGH_L to 160
    BoardSignalKind.ATTACK -> ToneGenerator.TONE_CDMA_MED_SS to 200
    BoardSignalKind.BANISHED -> ToneGenerator.TONE_CDMA_MED_L to 180
    BoardSignalKind.SENT_TO_GRAVEYARD -> ToneGenerator.TONE_CDMA_LOW_SS to 160
    BoardSignalKind.SUMMONED -> ToneGenerator.TONE_CDMA_HIGH_SS to 140
    BoardSignalKind.ACTIVATED -> ToneGenerator.TONE_PROP_BEEP to 120
    BoardSignalKind.DRAW -> ToneGenerator.TONE_PROP_ACK to 100
    BoardSignalKind.TARGETED -> ToneGenerator.TONE_PROP_BEEP to 80
    BoardSignalKind.RETURNED, BoardSignalKind.VANISHED -> ToneGenerator.TONE_CDMA_MED_L to 140
}

/**
 * 盤面の出来事の音。端末の内蔵トーンを使うので、音源ファイルは要らない。
 * 鳴らすのは演出が始まる瞬間なので、音と絵がずれない。
 */
class DuelSounds internal constructor(private val tones: ToneGenerator?) {
    fun play(kind: BoardSignalKind) {
        val (tone, ms) = signalTone(kind)
        runCatching { tones?.startTone(tone, ms) }
    }
}

@Composable
fun rememberDuelSounds(): DuelSounds {
    val tones = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { tones?.release() } }
    }
    return remember(tones) { DuelSounds(tones) }
}
