package app.einkradio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Station art for the car. Pandora's station images aren't always square or large, and the car draws
 * whatever it is given into a square card, so a wide or small image comes out stretched or soft. This
 * turns each one into a centred 512 px square and serves that from the app's own cache.
 */
object ArtCache {
    private const val SIDE = 512
    private const val MAX_BYTES = 6L * 1024 * 1024
    private val ID = Regex("[0-9a-f]{32}")
    private val SIZE_IN_URL = Regex("W\\d+H\\d+")

    /** Which original image each id stands for. Only images the app itself registered can be served. */
    private val sources = ConcurrentHashMap<String, String>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** A content address the car can load for [url]; the square image is made the first time it is read. */
    fun uriFor(context: Context, url: String): Uri {
        val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        sources[id] = url
        return Uri.Builder().scheme("content").authority("${context.packageName}.art").appendPath(id).build()
    }

    fun fileFor(context: Context, id: String): File? {
        if (!ID.matches(id)) return null
        val dir = File(context.cacheDir, "art").apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        if (out.exists() && out.length() > 0) return out
        val url = sources[id] ?: return null

        // Pandora serves the same picture at several sizes; try the large one first, then the original.
        for (candidate in listOf(url.replace(SIZE_IN_URL, "W500H500"), url).distinct()) {
            val bitmap = try { fetch(candidate) } catch (e: Exception) { null } ?: continue
            val tmp = File(dir, "$id.tmp")
            tmp.outputStream().use { centreSquare(bitmap).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (tmp.renameTo(out)) return out
        }
        return null
    }

    private fun fetch(url: String): Bitmap? {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** Cuts the largest centred square out of the image and scales it to [SIDE]. */
    private fun centreSquare(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return if (side == SIDE) cropped else Bitmap.createScaledBitmap(cropped, SIDE, SIDE, true)
    }
}

/** Lets Android Auto read the images made by [ArtCache]. It serves nothing else. */
class ArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val id = uri.lastPathSegment ?: throw FileNotFoundException("No image id")
        val file = ArtCache.fileFor(ctx, id) ?: throw FileNotFoundException("Unknown image")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
