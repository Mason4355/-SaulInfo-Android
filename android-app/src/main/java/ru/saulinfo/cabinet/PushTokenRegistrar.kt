package ru.saulinfo.cabinet

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PushTokenRegistrar {
    private const val PREFS = "saulinfo_push"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_FCM_TOKEN = "fcm_token"

    fun saveAccessToken(context: Context, accessToken: String) {
        if (accessToken.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply()
    }

    fun saveFcmToken(context: Context, fcmToken: String) {
        if (fcmToken.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, fcmToken)
            .apply()
    }

    fun registerSavedToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        val fcmToken = prefs.getString(KEY_FCM_TOKEN, "").orEmpty()
        register(context, accessToken, fcmToken)
    }

    fun subscribeToBroadcastTopic() {
        val topic = BuildConfig.ANDROID_PUSH_TOPIC.trim()
        if (!topic.matches(Regex("[A-Za-z0-9_.~%-]{1,900}"))) return
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(topic)
        } catch (_: Exception) {
            // Firebase push is active only when google-services.json is provided for the installation.
        }
    }

    fun register(context: Context, accessToken: String, fcmToken: String) {
        if (accessToken.isBlank() || fcmToken.isBlank()) return
        saveAccessToken(context, accessToken)
        saveFcmToken(context, fcmToken)
        Thread {
            try {
                val url = Uri.parse(BuildConfig.CABINET_URL)
                    .buildUpon()
                    .path("/api/cabinet/mobile/push-token")
                    .build()
                    .toString()
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                val payload = JSONObject()
                    .put("token", fcmToken)
                    .put("platform", "android")
                    .put(
                        "app_instance_id",
                        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty(),
                    )
                    .toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                connection.inputStream.close()
                connection.disconnect()
            } catch (_: Exception) {
                // Token registration is retried on next page load or token refresh.
            }
        }.start()
    }
}
