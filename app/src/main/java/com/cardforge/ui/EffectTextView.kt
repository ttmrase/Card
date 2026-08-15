package com.cardforge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Accent
import com.cardforge.ui.theme.Gold

/**
 * カードテキストを、欄ごとに色を分けて表示する。
 *
 * 【場所】【条件】【コスト】といった前置きは控えめな色、効果そのものは
 * 通常の色にすることで、改行を増やさずに区切りを分かるようにしている。
 */
@Composable
fun EffectTextView(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall
) {
    Text(
        annotateEffectText(
            text = text,
            tag = Accent,
            number = Gold,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
            body = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier,
        style = style
    )
}

/**
 * カードテキストに色を付ける。
 *
 * - 【…】の見出しは [tag]、①②などの番号は [number]
 * - 前置き（効果の前に置かれる欄）は [muted]
 * - 効果そのものは [body]
 */
fun annotateEffectText(
    text: String,
    tag: Color,
    number: Color,
    muted: Color,
    body: Color
): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append("\n")
        appendLine(line, tag, number, muted, body)
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLine(
    line: String,
    tag: Color,
    number: Color,
    muted: Color,
    body: Color
) {
    var rest = line

    // 「①：」のような効果番号。
    val colon = rest.indexOf('：')
    if (colon in 1..2 && rest.take(colon).all { it in CIRCLED_CHARS }) {
        withStyle(SpanStyle(color = number, fontWeight = FontWeight.Bold)) {
            append(rest.substring(0, colon + 1))
        }
        rest = rest.substring(colon + 1)
    }

    // 「●場合分けの条件：効果」。
    if (rest.startsWith("●")) {
        val split = rest.indexOf('：')
        if (split > 0) {
            withStyle(SpanStyle(color = tag, fontWeight = FontWeight.Bold)) {
                append(rest.substring(0, 1))
            }
            appendSections(rest.substring(1, split + 1), tag, muted)
            withStyle(SpanStyle(color = body)) { append(rest.substring(split + 1)) }
            return
        }
    }

    // 前置きと効果本体の区切り。
    val arrow = rest.indexOf(EffectTextRenderer.EFFECT_ARROW)
    if (arrow >= 0) {
        appendSections(rest.substring(0, arrow), tag, muted)
        withStyle(SpanStyle(color = tag, fontWeight = FontWeight.Bold)) {
            append(EffectTextRenderer.EFFECT_ARROW)
        }
        withStyle(SpanStyle(color = body)) {
            append(rest.substring(arrow + EffectTextRenderer.EFFECT_ARROW.length))
        }
        return
    }

    // 効果番号より前の共通指定（行まるごとが前置き）。
    if (rest.startsWith("【")) {
        appendSections(rest, tag, muted)
        return
    }

    withStyle(SpanStyle(color = body)) { append(rest) }
}

/** 【…】の見出しだけ色を変えつつ、前置きを控えめな色で書き出す。 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendSections(
    text: String,
    tag: Color,
    muted: Color
) {
    var i = 0
    while (i < text.length) {
        val open = text.indexOf('【', i)
        if (open < 0) {
            withStyle(SpanStyle(color = muted)) { append(text.substring(i)) }
            return
        }
        if (open > i) {
            withStyle(SpanStyle(color = muted)) { append(text.substring(i, open)) }
        }
        val close = text.indexOf('】', open)
        if (close < 0) {
            withStyle(SpanStyle(color = muted)) { append(text.substring(open)) }
            return
        }
        withStyle(SpanStyle(color = tag, fontWeight = FontWeight.Bold)) {
            append(text.substring(open, close + 1))
        }
        i = close + 1
    }
}

private val CIRCLED_CHARS =
    "①②③④⑤⑥⑦⑧⑨⑩()０１２３４５６７８９0123456789".toSet()
