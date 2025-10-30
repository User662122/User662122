package com.chesschat.app

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bitmap Pool for efficient memory management
 * Reuses bitmaps to prevent memory leaks and reduce garbage collection
 */
class BitmapPool(private val maxPoolSize: Int = 5) {
    
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    
    fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        synchronized(pool) {
            val bitmap = pool.poll()
            return if (bitmap != null && bitmap.width == width && 
                      bitmap.height == height && bitmap.config == config) {
                bitmap
            } else {
                bitmap?.recycle()
                Bitmap.createBitmap(width, height, config)
            }
        }
    }
    
    fun recycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        
        synchronized(pool) {
            if (pool.size < maxPoolSize) {
                pool.offer(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }
    
    fun clear() {
        synchronized(pool) {
            pool.forEach { it.recycle() }
            pool.clear()
        }
    }
}
