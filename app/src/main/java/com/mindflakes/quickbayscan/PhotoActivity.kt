package com.mindflakes.quickbayscan

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// To use a different model, change this one line.
private const val MODEL = "claude-haiku-5-5"

private const val PROMPT =
    "Identify the main item in this photo so it can be searched on eBay. " +
        "Reply with ONLY a concise search query of 3 to 8 words: brand, model or pattern, " +
        "item type and any key distinguishing words. No quotes, no punctuation, no explanation. " +
        "If you are unsure, give the best generic description."

private class AuthException : Exception()

class PhotoActivity : ComponentActivity() {

    private lateinit var photoFile: File
    private lateinit var photoUri: Uri
    private var progress: AlertDialog? = null

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            if (saved) identify() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoFile = File(cacheDir, "item.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        if (savedInstanceState == null) {
            photoFile.delete()
            photoFile.createNewFile()
            if (apiKey().isEmpty()) askForKey() else takePhoto.launch(photoUri)
        }
    }

    private fun prefs() = getSharedPreferences("photo", Context.MODE_PRIVATE)

    private fun apiKey(): String = prefs().getString("key", "") ?: ""

    private fun askForKey() {
        val input = EditText(this)
        input.hint = "sk-ant-..."
        input.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("Anthropic API key")
            .setMessage("Paste your key. It is stored only on this phone.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isEmpty()) {
                    finish()
                } else {
                    prefs().edit().putString("key", key).apply()
                    takePhoto.launch(photoUri)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun identify() {
        progress = AlertDialog.Builder(this)
            .setMessage("Identifying item...")
            .setCancelable(false)
            .show()
        thread {
            try {
                val query = askModel(shrink(photoFile))
                runOnUiThread {
                    progress?.dismiss()
                    if (query.isBlank()) {
                        Toast.makeText(this, "Could not identify the item", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        openEbay(query)
                    }
                }
            } catch (e: AuthException) {
                prefs().edit().remove("key").apply()
                runOnUiThread {
                    progress?.dismiss()
                    Toast.makeText(this, "API key was rejected. Enter it again.", Toast.LENGTH_LONG).show()
                    askForKey()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress?.dismiss()
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun openEbay(query: String) {
        val url = "https://www.ebay.com/sch/i.html?_from=R40&_nkw=${Uri.encode(query)}" +
            "&_sacat=0&rt=nc&LH_Sold=1&LH_Complete=1"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        finish()
    }

    // Downscale to about 1280px on the long side, fix rotation, return JPEG bytes.
    private fun shrink(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.path, opts)
            ?: throw Exception("Could not read the photo")

        val rotation = when (
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, 1280f / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
        return bytes.toByteArray()
    }

    private fun askModel(jpeg: ByteArray): String {
        val image = JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            )
        val text = JSONObject().put("type", "text").put("text", PROMPT)
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(image).put(text))
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 60)
            .put("messages", JSONArray().put(message))
            .toString()

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey())
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code == 401) throw AuthException()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code ${response.take(200)}")

            val answer = JSONObject(response)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
            return answer.replace(Regex("[\\r\\n\"]"), " ").trim()
        } finally {
            conn.disconnect()
        }
    }
}
