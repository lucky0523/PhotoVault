package com.huoyi.photovault.data.network.endpoint

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Loader for the shared vectors of 服务器二维码协议 v1（见 protocol/README.md 与
 * protocol/server-qr-v1.json）. The server's pytest reads the same file.
 *
 * Walks up from `user.dir` until `protocol/server-qr-v1.json` is found; a missing file
 * fails the test instead of skipping it. No test-resources wiring in build.gradle.kts.
 */
internal object ServerQrVectors {

    private const val RELATIVE_PATH = "protocol/server-qr-v1.json"

    private val root: JsonObject by lazy { load() }

    /** One vector group (e.g. "decode") as a list of objects. */
    fun group(name: String): List<JsonObject> {
        val array = root.getAsJsonArray(name) ?: error("vector group '$name' missing in $RELATIVE_PATH")
        return array.map { it.asJsonObject }
    }

    private fun load(): JsonObject {
        val start = File(checkNotNull(System.getProperty("user.dir")) { "user.dir is not set" }).absoluteFile
        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, RELATIVE_PATH)
            if (candidate.isFile) {
                return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            }
            dir = dir.parentFile
        }
        error("$RELATIVE_PATH not found in $start or any parent directory")
    }
}

/** String value of [key], or null when the key is absent or JSON null. */
internal fun JsonObject.str(key: String): String? =
    get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
