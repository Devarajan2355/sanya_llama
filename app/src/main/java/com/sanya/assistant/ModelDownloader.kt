package com.sanya.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a model file to app's filesDir with optional SHA-256 verification.
 * Use from a coroutine (lifecycleScope.launch { ... }).
 */
suspend fun downloadModel(
    context: Context,
    modelUrl: String,
    outFileName: String,
    expectedSha256Hex: String? = null,
    onProgress: (percent: Int) -> Unit = {}
): File = withContext(Dispatchers.IO) {
    val dest = File(context.filesDir, outFileName)
    if (dest.exists()) {
        if (expectedSha256Hex == null || checkSha256(dest, expectedSha256Hex)) return@withContext dest
        dest.delete()
    }

    val url = URL(modelUrl)
    (url.openConnection() as HttpURLConnection).run {
        connectTimeout = 30_000
        readTimeout = 30_000
        requestMethod = "GET"
        doInput = true
        connect()
        if (responseCode !in 200..299) throw Exception("HTTP ${responseCode}")
        val length = contentLengthLong
        inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var total = 0L
                while (input.read(buf).also { read = it } > 0) {
                    output.write(buf, 0, read)
                    total += read
                    if (length > 0) {
                        val p = (total * 100 / length).toInt()
                        onProgress(p.coerceIn(0, 100))
                    }
                }
            }
        }
    }

    if (expectedSha256Hex != null && !checkSha256(dest, expectedSha256Hex)) {
        dest.delete()
        throw Exception("Checksum mismatch")
    }
    dest
}

private fun checkSha256(file: File, expectedHex: String): Boolean {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buf).also { read = it } > 0) md.update(buf, 0, read)
    }
    val actual = md.digest().joinToString("") { "%02x".format(it) }
    return actual.equals(expectedHex, ignoreCase = true)
}
