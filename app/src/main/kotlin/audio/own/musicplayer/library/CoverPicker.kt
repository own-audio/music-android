package audio.own.musicplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a chosen picture is made into before it goes to the server: at most 1600 px a side, JPEG. */
object CoverImages {
    const val MAX_PIXELS = 1600
    private const val QUALITY = 88

    /** The longest side brought down to [max], never up. */
    fun fit(width: Int, height: Int, max: Int = MAX_PIXELS): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= max || longest <= 0) return width to height
        val scale = max.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** The picture at [uri] as JPEG bytes, or null when it can't be read. */
    suspend fun jpeg(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = fit(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                // A software bitmap: hardware ones can't be compressed.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull()
    }
}

/** The system's photo picker, images only; [onPicked] gets the JPEG to send. */
@Composable
fun rememberCoverPicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { CoverImages.jpeg(context, uri)?.let(onPicked) }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
