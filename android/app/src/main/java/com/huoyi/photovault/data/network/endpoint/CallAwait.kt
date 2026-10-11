package com.huoyi.photovault.data.network.endpoint

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Suspends on an OkHttp [Call] via `enqueue`, never blocking the caller's thread
 * (design §4.2). Cancelling the coroutine cancels the call; a response that arrives
 * after cancellation is closed so its connection is not leaked.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
