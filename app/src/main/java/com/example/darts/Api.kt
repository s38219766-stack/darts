package com.example.darts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    // Supabase → Project Settings → API
    const val SUPABASE_URL = https://obewdcvaxuaehoyzydsx.supabase.co
    // Только публичный ключ (anon / publishable). НЕ service_role и НЕ secret!
    const val SUPABASE_KEY = sb_publishable_vAtJEF90ILV2MQtVLAJp7Q_TlCt0do-
}

class Session(val userId: String, val accessToken: String)
class Profile(val balance: Long, val promoActive: Boolean)

class Api(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private var session: Session? = null

    private fun request(method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val conn = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun parseSession(text: String): Session {
        val j = JSONObject(text)
        prefs.edit().putString("refresh", j.getString("refresh_token")).apply()
        return Session(j.getJSONObject("user").getString("id"), j.getString("access_token"))
    }

    // Анонимный вход: аккаунт создаётся сам, потом токен обновляется
    suspend fun ensureSession(): Session = withContext(Dispatchers.IO) {
        session?.let { return@withContext it }

        val refresh = prefs.getString("refresh", null)
        if (refresh != null) {
            val (c, t) = request(
                "POST", "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh).toString(), null
            )
            if (c in 200..299) {
                return@withContext parseSession(t).also { session = it }
            }
            // создаём новый аккаунт только если токен точно недействителен
            if (c != 400 && c != 401 && c != 403) error("Сервер недоступен ($c)")
        }

        val (c, t) = request("POST", "/auth/v1/signup", "{}", null)
        if (c !in 200..299) error("Вход не удался ($c): $t")
        parseSession(t).also { session = it }
    }

    suspend fun getProfile(): Profile = withContext(Dispatchers.IO) {
        val s = ensureSession()
        val (c, t) = request("GET", "/rest/v1/profiles?select=balance,promo_active", null, s.accessToken)
        if (c !in 200..299) error("Профиль ($c): $t")
        val o = JSONArray(t).getJSONObject(0)
        Profile(o.getLong("balance"), o.getBoolean("promo_active"))
    }

    suspend fun redeemPromo(code: String): Boolean = withContext(Dispatchers.IO) {
        val s = ensureSession()
        val (c, t) = request(
            "POST", "/rest/v1/rpc/redeem_promo",
            JSONObject().put("p_code", code).toString(), s.accessToken
        )
        if (c !in 200..299) error("Промокод ($c): $t")
        t.trim() == "true"
    }
}
