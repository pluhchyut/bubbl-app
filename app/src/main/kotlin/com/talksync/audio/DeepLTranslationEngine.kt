package com.talksync.audio

// small deepl client so translation http stuff stays out of the view model mess.

import com.talksync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepLTranslationEngine {

    companion object {
        const val PLACEHOLDER_API_KEY = "YOUR_DEEPL_API_KEY_HERE"
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = BuildConfig.DEEPL_API_KEY.isNotBlank() &&
                BuildConfig.DEEPL_API_KEY != PLACEHOLDER_API_KEY

    suspend fun translate(text: String, sourceLang: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        try {
            // DeepL deprecated form-body auth_key in Nov 2025.
            // Auth is now header-only: Authorization: DeepL-Auth-Key <key>
            val body = FormBody.Builder()
                .add("text", text)
                .add("source_lang", sourceLang.uppercase().take(2))
                .add("target_lang", "EN")
                .build()
            val request = Request.Builder()
                .url("https://api-free.deepl.com/v2/translate")
                .header("Authorization", "DeepL-Auth-Key ${BuildConfig.DEEPL_API_KEY}")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyString = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyString)
                json.optJSONArray("translations")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
