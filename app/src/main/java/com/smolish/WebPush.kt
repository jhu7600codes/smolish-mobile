package com.smolish

import android.content.Context
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real web push for smolish.com, which a webview normally can't do. The page gets a push
 * subscription whose endpoint is a private ntfy.sh topic; smolish's server sends its encrypted
 * pushes there, NotifyService listens to the topic and decrypts them here (RFC 8291, aes128gcm)
 * with keys that never leave the phone.
 */
object WebPush {
    const val NTFY = "https://ntfy.sh"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("push", 0)

    fun isActive(ctx: Context) = prefs(ctx).getBoolean("active", false)
    fun topic(ctx: Context) = prefs(ctx).getString("topic", null)
    fun lastId(ctx: Context) = prefs(ctx).getString("last_id", null)
    fun setLastId(ctx: Context, id: String) = prefs(ctx).edit().putString("last_id", id).apply()

    /** The subscription the page sees, as JSON {endpoint, p256dh, auth}, or null. */
    fun subscriptionJson(ctx: Context): String? {
        val p = prefs(ctx)
        if (!p.getBoolean("active", false)) return null
        val topic = p.getString("topic", null) ?: return null
        return org.json.JSONObject()
            .put("endpoint", "$NTFY/$topic")
            .put("p256dh", p.getString("pub", ""))
            .put("auth", p.getString("auth", ""))
            .toString()
    }

    /** Makes (or reuses) the keys and topic and turns push on. */
    fun subscribe(ctx: Context): String? {
        val p = prefs(ctx)
        if (p.getString("topic", null) == null) {
            val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val kp = kpg.generateKeyPair()
            val rnd = SecureRandom()
            val auth = ByteArray(16).also { rnd.nextBytes(it) }
            // ntfy treats topics starting with "up" + 12 characters as push topics (unifiedpush style)
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val topic = "up" + (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            p.edit()
                .putString("topic", topic)
                .putString("priv", b64(kp.private.encoded))
                .putString("pub", b64url(raw(kp.public as ECPublicKey)))
                .putString("auth", b64url(auth))
                .remove("last_id")
                .apply()
        }
        p.edit().putBoolean("active", true).apply()
        return subscriptionJson(ctx)
    }

    /** Turned off on the site: forget the keys, a new subscribe gets a fresh topic. */
    fun unsubscribe(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** Decrypts one push message body (aes128gcm). Returns the plaintext or null. */
    fun decrypt(ctx: Context, body: ByteArray): String? {
        val p = prefs(ctx)
        return decrypt(body, p.getString("priv", "")!!, p.getString("pub", "")!!, p.getString("auth", "")!!)
    }

    /** [privB64] PKCS8 base64, [pubB64url] / [authB64url] as given to the site. */
    fun decrypt(body: ByteArray, privB64: String, pubB64url: String, authB64url: String): String? = runCatching {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(privB64))) as ECPrivateKey
        val uaPublic = unb64url(pubB64url)
        val auth = unb64url(authB64url)

        // header: salt(16) | record size(4) | key id length(1) | key id (the server's public key)
        val salt = body.copyOfRange(0, 16)
        val idLen = body[20].toInt() and 0xff
        val asPublic = body.copyOfRange(21, 21 + idLen)
        val cipherText = body.copyOfRange(21 + idLen, body.size)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(publicKey(asPublic, priv), true)
        val ecdh = ka.generateSecret()

        val prkKey = hmac(auth, ecdh)
        val ikm = hmac(prkKey, "WebPush: info".toByteArray() + 0 + uaPublic + asPublic + 1).copyOf(32)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val plain = c.doFinal(cipherText)
        // padding: the text, then a 0x02 delimiter, then zeros
        var end = plain.size - 1
        while (end >= 0 && plain[end].toInt() == 0) end--
        String(plain, 0, maxOf(end, 0), Charsets.UTF_8)
    }.getOrNull()

    /**
     * The sender's side (what smolish's server does), for the developer "test push" button:
     * encrypts [text] for our own subscription and posts it to our ntfy topic, so it comes back
     * through the whole chain: ntfy -> NotifyService -> decrypt -> notification. Network, call off the main thread.
     */
    fun sendTest(ctx: Context, text: String): Boolean = runCatching {
        val p = prefs(ctx)
        val topic = p.getString("topic", null) ?: return false
        val uaPublic = unb64url(p.getString("pub", "")!!)
        val auth = unb64url(p.getString("auth", "")!!)
        val body = encrypt(text.toByteArray(), uaPublic, auth)
        val c = java.net.URL("$NTFY/$topic").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Encoding", "aes128gcm")
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("TTL", "60")
        c.outputStream.use { it.write(body) }
        c.responseCode in 200..299
    }.getOrDefault(false)

    fun encrypt(plain: ByteArray, uaPublic: ByteArray, auth: ByteArray): ByteArray {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val asPublic = raw(kp.public as ECPublicKey)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(publicKey(uaPublic, kp.private as ECPrivateKey), true)
        val ecdh = ka.generateSecret()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val prkKey = hmac(auth, ecdh)
        val ikm = hmac(prkKey, "WebPush: info".toByteArray() + 0 + uaPublic + asPublic + 1).copyOf(32)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plain + 2)
        val header = salt + byteArrayOf(0, 0, 16, 0) + byteArrayOf(65) + asPublic // record size 4096
        return header + ct
    }

    private operator fun ByteArray.plus(b: Int) = this + byteArrayOf(b.toByte())

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** 65-byte uncompressed point (0x04 | x | y), the format web push uses. */
    private fun raw(k: ECPublicKey): ByteArray {
        fun fixed(n: BigInteger) = n.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }
        return byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
    }

    private fun publicKey(raw: ByteArray, like: ECPrivateKey) = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), like.params)
    )

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)
    private fun b64url(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64url(s: String) = java.util.Base64.getUrlDecoder().decode(s.trimEnd('='))
}
