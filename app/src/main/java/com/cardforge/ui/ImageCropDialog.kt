package com.cardforge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Ink

/** 切り抜いた範囲。いずれも 0.0〜1.0 の割合。 */
data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * カードのイラスト用に画像を切り抜く画面。
 *
 * 枠はカードのイラスト枠と同じ 3:4。指で動かして拡大縮小し、
 * 枠に収めた部分がイラストになる。
 */
@Composable
fun ImageCropDialog(
    imagePath: String,
    onDismiss: () -> Unit,
    onConfirm: (CropRect) -> Unit
) {
    val bitmap = rememberCardImage(imagePath)

    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var frameSize by remember { mutableStateOf(IntSizeHolder(0, 0)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "イラストの切り抜き",
                    style = MaterialTheme.typography.titleLarge,
                    color = Gold
                )
                Text(
                    "指でドラッグして位置を、つまんで拡大縮小を決めます。枠の中がイラストになります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(3f / 4f)
                            .onSizeChanged { frameSize = IntSizeHolder(it.width, it.height) }
                            .clipToBounds()
                            .background(Color.Black)
                            .border(2.dp, Gold)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            }
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = zoom,
                                        scaleY = zoom,
                                        translationX = offsetX,
                                        translationY = offsetY
                                    )
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        zoom = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }) { Text("リセット") }

                    OutlinedButton(onClick = onDismiss) { Text("切り抜かない") }

                    Button(onClick = {
                        onConfirm(
                            computeCrop(
                                zoom = zoom,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                frameWidth = frameSize.width.toFloat(),
                                frameHeight = frameSize.height.toFloat()
                            )
                        )
                    }) { Text("この範囲で決定") }
                }
                Spacer(Modifier.height(LocalDensity.current.run { 0.dp }))
            }
        }
    }
}

private data class IntSizeHolder(val width: Int, val height: Int)

/**
 * 表示の拡大率と移動量から、元画像のどの範囲が枠に映っているかを求める。
 *
 * 画像は ContentScale.Crop で枠いっぱいに表示されているので、枠と
 * 表示画像の対応は「枠全体 = 拡大率1のときの画像全体」として扱える。
 */
internal fun computeCrop(
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    frameWidth: Float,
    frameHeight: Float
): CropRect {
    if (frameWidth <= 0f || frameHeight <= 0f || zoom <= 0f) {
        return CropRect(0f, 0f, 1f, 1f)
    }
    // 枠の左上が、拡大前の画像のどこに当たるか（割合）。
    val visibleWidth = 1f / zoom
    val visibleHeight = 1f / zoom
    val centerX = 0.5f - offsetX / (frameWidth * zoom)
    val centerY = 0.5f - offsetY / (frameHeight * zoom)

    val left = (centerX - visibleWidth / 2f).coerceIn(0f, 1f - visibleWidth.coerceAtMost(1f))
    val top = (centerY - visibleHeight / 2f).coerceIn(0f, 1f - visibleHeight.coerceAtMost(1f))
    return CropRect(
        left = left,
        top = top,
        width = visibleWidth.coerceAtMost(1f - left),
        height = visibleHeight.coerceAtMost(1f - top)
    )
}
