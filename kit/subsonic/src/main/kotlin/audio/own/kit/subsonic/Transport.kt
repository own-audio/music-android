package audio.own.kit.subsonic

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Saved answers to read requests, so a screen seen once opens again with no network. The app
 * supplies the storage; keys never contain the password, a token or a salt.
 */
interface ResponseCache {
    fun data(key: String): ByteArray?

    fun store(key: String, data: ByteArray)
}

/** Why something failed, for the app's log. Messages carry the path only, never the query: it holds the sign-in. */
fun interface NetworkLog {
    fun error(message: String)

    companion object {
        val None = NetworkLog { }
    }
}

internal class HttpAnswer(val code: Int, val body: ByteArray)

/** Runs the call without blocking a thread, and cancels it when the coroutine is cancelled. */
internal suspend fun Call.awaitAnswer(): HttpAnswer = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val answer = try {
                    response.use { HttpAnswer(it.code, it.body.bytes()) }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(answer)
            }
        },
    )
}

/**
 * The decoder's message up to where it quotes the input: the quote can hold a token or a signed
 * address, and the part before it already says which field failed.
 */
internal fun Exception.whereItFailed(): String = message.orEmpty().substringBefore("JSON input").trim().take(300)
