package com.salvage.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url.isNullOrEmpty()) { view.setImageDrawable(null); return }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        scope.launch {
            val bmp = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null } ?: return@launch
            cache.put(url, bmp)
            withContext(Dispatchers.Main) {
                if (view.tag == url) view.setImageBitmap(bmp)
            }
        }
    }
}
