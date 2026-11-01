package com.adong.adchat.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.adong.adchat.data.applyNetworkTrust
import com.adong.adchat.data.withCancellableResponse
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class BilibiliTvSession(
    val mid: Long,
    val accessToken: String,
    val refreshToken: String = "",
    val cookieHeader: String = "",
    val uname: String = "",
    val vipLabel: String = "",
    val expiresAtEpochMs: Long = 0L
) {
    val isValid: Boolean
        get() = accessToken.isNotBlank() && (expiresAtEpochMs <= 0L || System.currentTimeMillis() < expiresAtEpochMs)
}

data class BilibiliTvQrSession(
    val authCode: String,
    val authUrl: String
)

sealed interface BilibiliTvPollResult {
    data object WaitingScan : BilibiliTvPollResult
    data object ScannedWaitingConfirm : BilibiliTvPollResult
    data object Expired : BilibiliTvPollResult
    data class Authorized(val session: BilibiliTvSession) : BilibiliTvPollResult
    data class Failed(val message: String) : BilibiliTvPollResult
}

/**
 * Hardware-backed encrypted credential store for Bilibili TV `access_key` & cookies,
 * enabling 1080P / 1080P60 / 4K watermark-free downloads across both in-app and Star Island flows.
 */
class BilibiliTvAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): BilibiliTvSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val token = decryptSecret(root.optString("accessToken"))
            if (token.isBlank()) return null
            val session = BilibiliTvSession(
                mid = root.optLong("mid", 0L),
                accessToken = token,
                refreshToken = decryptSecret(root.optString("refreshToken")),
                cookieHeader = decryptSecret(root.optString("cookieHeader")),
                uname = root.optString("uname"),
                vipLabel = root.optString("vipLabel"),
                expiresAtEpochMs = root.optLong("expiresAtEpochMs", 0L)
            )
            session.takeIf { it.isValid }
        }.getOrNull()
    }

    fun save(session: BilibiliTvSession) {
        val json = JSONObject()
            .put("mid", session.mid)
            .put("accessToken", encryptSecret(session.accessToken))
            .put("refreshToken", encryptSecret(session.refreshToken))
            .put("cookieHeader", encryptSecret(session.cookieHeader))
            .put("uname", session.uname)
            .put("vipLabel", session.vipLabel)
            .put("expiresAtEpochMs", session.expiresAtEpochMs)
        prefs.edit().putString(KEY_SESSION, json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun encryptSecret(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            "enc:v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        }.getOrElse { value }
    }

    private fun decryptSecret(value: String): String {
        if (!value.startsWith("enc:v1:")) return value
        return runCatching {
            val parts = value.split(':', limit = 4)
            val iv = Base64.decode(parts[2], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[3], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS_NAME = "adchat_bilibili_tv_auth"
        private const val KEY_SESSION = "tvSessionV1"
        private const val KEY_ALIAS = "aster_bili_tv_key_v1"

        @Volatile
        private var currentSessionOverride: BilibiliTvSession? = null

        fun currentSession(context: Context? = null): BilibiliTvSession? {
            currentSessionOverride?.takeIf { it.isValid }?.let { return it }
            val ctx = context ?: return null
            return BilibiliTvAuthStore(ctx).load()?.also { currentSessionOverride = it }
        }

        fun updateCachedSession(session: BilibiliTvSession?) {
            currentSessionOverride = session
        }
    }
}

class BilibiliTvAuthClient(
    private val client: OkHttpClient = defaultClient()
) {
    suspend fun requestQrCode(): BilibiliTvQrSession = withContext(Dispatchers.IO) {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val params = sortedMapOf(
            "appkey" to BilibiliMediaPolicy.TV_APP_KEY,
            "local_id" to "0",
            "ts" to ts
        )
        val signed = BilibiliMediaPolicy.signTvParams(params)
        val bodyBuilder = FormBody.Builder()
        signed.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val request = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code")
            .header("User-Agent", "Mozilla/5.0")
            .post(bodyBuilder.build())
            .build()
        client.newCall(request).withCancellableResponse { response ->
            if (!response.isSuccessful) error("B 站授权服务返回 ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val code = json.optInt("code", -1)
            if (code != 0) error(json.optString("message").ifBlank { "获取授权码失败（$code）" })
            val data = json.optJSONObject("data") ?: error("B 站未返回授权链接")
            val authCode = data.optString("auth_code")
            val url = data.optString("url")
            if (authCode.isBlank() || url.isBlank()) error("B 站授权码不完整")
            BilibiliTvQrSession(authCode = authCode, authUrl = url)
        }
    }

    suspend fun pollQrCode(authCode: String): BilibiliTvPollResult = withContext(Dispatchers.IO) {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val params = sortedMapOf(
            "appkey" to BilibiliMediaPolicy.TV_APP_KEY,
            "auth_code" to authCode,
            "local_id" to "0",
            "ts" to ts
        )
        val signed = BilibiliMediaPolicy.signTvParams(params)
        val bodyBuilder = FormBody.Builder()
        signed.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val request = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-tv-login/qrcode/poll")
            .header("User-Agent", "Mozilla/5.0")
            .post(bodyBuilder.build())
            .build()
        val json = client.newCall(request).withCancellableResponse { response ->
            if (!response.isSuccessful) return@withCancellableResponse null
            runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        } ?: return@withContext BilibiliTvPollResult.Failed("网络波动，正在继续等待确认")

        when (val code = json.optInt("code", -1)) {
            86039 -> BilibiliTvPollResult.WaitingScan
            86090 -> BilibiliTvPollResult.ScannedWaitingConfirm
            86038 -> BilibiliTvPollResult.Expired
            0 -> {
                val data = json.optJSONObject("data")
                    ?: return@withContext BilibiliTvPollResult.Failed("授权信息为空")
                val tokenInfo = data.optJSONObject("token_info") ?: data
                val accessToken = tokenInfo.optString("access_token").ifBlank { data.optString("access_token") }
                if (accessToken.isBlank()) {
                    return@withContext BilibiliTvPollResult.Failed("未获取到 access_token")
                }
                val refreshToken = tokenInfo.optString("refresh_token").ifBlank { data.optString("refresh_token") }
                val mid = tokenInfo.optLong("mid", data.optLong("mid", 0L))
                val expiresInSec = tokenInfo.optLong("expires_in", data.optLong("expires_in", 15_552_000L))
                val cookieHeader = extractCookieHeader(data.optJSONObject("cookie_info"))
                val (uname, vipLabel) = fetchUserProfile(cookieHeader, accessToken)
                BilibiliTvPollResult.Authorized(
                    BilibiliTvSession(
                        mid = mid,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        cookieHeader = cookieHeader,
                        uname = uname.ifBlank { if (mid > 0L) "UID $mid" else "已授权账号" },
                        vipLabel = vipLabel,
                        expiresAtEpochMs = System.currentTimeMillis() + expiresInSec.coerceAtLeast(86_400L) * 1000L
                    )
                )
            }
            else -> BilibiliTvPollResult.Failed(json.optString("message").ifBlank { "授权未完成（$code）" })
        }
    }

    private fun extractCookieHeader(cookieInfo: JSONObject?): String {
        val cookies = cookieInfo?.optJSONArray("cookies") ?: return ""
        val pairs = mutableListOf<String>()
        for (i in 0 until cookies.length()) {
            val item = cookies.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val value = item.optString("value").trim()
            if (name.isNotBlank() && value.isNotBlank()) {
                pairs += "$name=$value"
            }
        }
        return pairs.joinToString("; ")
    }

    private suspend fun fetchUserProfile(cookieHeader: String, accessToken: String): Pair<String, String> {
        // 1. Try web nav if cookies were returned
        if (cookieHeader.isNotBlank()) {
            val navProfile = runCatching {
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/nav")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Cookie", cookieHeader)
                    .get()
                    .build()
                client.newCall(req).withCancellableResponse { resp ->
                    val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")
                    if (data != null && data.optBoolean("isLogin")) {
                        val uname = data.optString("uname")
                        val vipActive = data.optInt("vipStatus") == 1
                        val vipText = data.optJSONObject("vip_label")?.optString("text").orEmpty()
                            .ifBlank { if (vipActive) "大会员" else "1080P 无水印" }
                        uname to (if (vipActive) "$vipText · 4K 无水印" else "1080P 无水印")
                    } else null
                }
            }.getOrNull()
            if (navProfile != null) return navProfile
        }
        // 2. Fallback to signed app myinfo endpoint
        return runCatching {
            val params = BilibiliMediaPolicy.signTvParams(
                sortedMapOf(
                    "access_key" to accessToken,
                    "appkey" to BilibiliMediaPolicy.TV_APP_KEY,
                    "ts" to (System.currentTimeMillis() / 1000L).toString()
                )
            )
            val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            val req = Request.Builder()
                .url("https://app.bilibili.com/x/v2/account/myinfo?$qs")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            client.newCall(req).withCancellableResponse { resp ->
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")
                val uname = data?.optString("name").orEmpty()
                val vipStatus = data?.optJSONObject("vip")?.optInt("status") ?: 0
                uname to (if (vipStatus == 1) "大会员 · 4K 无水印" else "1080P 无水印")
            }
        }.getOrDefault("" to "1080P 无水印")
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .applyNetworkTrust()
            .build()
    }
}
