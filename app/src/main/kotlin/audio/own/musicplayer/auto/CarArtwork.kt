package audio.own.musicplayer.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.scale
import audio.own.musicplayer.library.Covers
import coil3.SingletonImageLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pictures for Android Auto's lists. The car reads a row's artwork through a content URI, not
 * through the app's image loader, so each cover the browse tree names is handed out as
 * `content://<app>.artwork/art?id=<cover id>` and served small from a cache folder. Only covers
 * the tree itself named are served: other apps can't make this one fetch what they like.
 */
class CarArtwork(private val authority: String, private val folder: File) {
    private val named: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun uri(coverId: String?): Uri? {
        if (coverId.isNullOrBlank()) return null
        named += coverId
        return Uri.Builder().scheme("content").authority(authority).appendPath("art").appendQueryParameter("id", coverId).build()
    }

    fun isNamed(coverId: String): Boolean = coverId in named

    fun file(coverId: String): File = File(folder, sha1(coverId) + ".jpg")

    private fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val PX = 256
        const val LOAD_TIMEOUT_MS = 10_000L
        private const val JPEG_QUALITY = 80

        /** A cover as a small square JPEG, for the car and the widget: they read files, never the network. */
        suspend fun smallJpeg(context: Context, coverId: String, px: Int): ByteArray? {
            val bitmap = Covers.bitmap(context, SingletonImageLoader.get(context), coverId, px) ?: return null
            val square = if (bitmap.width == px && bitmap.height == px) bitmap else bitmap.scale(px, px)
            return ByteArrayOutputStream().use { out ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val context = context?.applicationContext ?: throw FileNotFoundException("not ready")
        val app = context as? audio.own.musicplayer.OwnAudioApp ?: throw FileNotFoundException("not ready")
        val id = uri.getQueryParameter("id") ?: throw FileNotFoundException("no picture")
        val car = app.container.carArtwork
        if (!car.isNamed(id)) throw FileNotFoundException("not one of the app's pictures")
        val file = car.file(id)
        if (!file.exists()) {
            val bytes = runBlocking(Dispatchers.IO) { withTimeoutOrNull(CarArtwork.LOAD_TIMEOUT_MS) { CarArtwork.smallJpeg(context, id, CarArtwork.PX) } }
                ?: throw FileNotFoundException("picture unavailable")
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            partial.writeBytes(bytes)
            partial.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
