package com.lanrex.sitecam.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ThumbnailCache {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    val io = Dispatchers.IO.limitedParallelism(4)
}

/** Square thumbnail of a photo or video, loaded from Android's thumbnail cache. */
@Composable
fun MediaThumbnail(uri: Uri, modifier: Modifier = Modifier, sizePx: Int = 320) {
    val context = LocalContext.current
    val key = "$uri@$sizePx"
    var bitmap by remember(key) { mutableStateOf(ThumbnailCache.cache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap == null) {
            bitmap = withContext(ThumbnailCache.io) {
                try {
                    context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                        .also { ThumbnailCache.cache.put(key, it) }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
