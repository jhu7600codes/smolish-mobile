package com.smolish

import android.content.Context
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Translates smolish.com (always English) into your language, inside the app.
 * Yandex first (the endpoint its own android app uses, it gets slang and memes), and when that's
 * down, google's free web translator takes over.
 */
object Translator {
    val LANGS = listOf("ru", "uk", "kk", "be", "uz", "de", "es", "fr", "it", "pt", "pl", "tr", "ar", "zh", "ja")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", 0)
    fun isOn(ctx: Context) = prefs(ctx).getBoolean("translate", false)
    fun setOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("translate", on).apply()
    fun lang(ctx: Context) = prefs(ctx).getString("translate_lang", null) ?: defaultLang()
    fun setLang(ctx: Context, l: String) = prefs(ctx).edit().putString("translate_lang", l).apply()
    /** Changes when the page needs a reload to show the new setting. */
    fun state(ctx: Context) = if (isOn(ctx)) lang(ctx) else ""

    private fun defaultLang() = Locale.getDefault().language.takeIf { it in LANGS } ?: "ru"

    /** "русский", "українська"... each language in its own words. */
    fun name(code: String) = Locale(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.titlecase(Locale(code)) }

    private val cache = LruCache<String, String>(6000)
    private val pool = Executors.newFixedThreadPool(2)
    private val sid = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var yandexDownUntil = 0L

    // ui words where the translator guesses wrong without context ("Follow" -> "Следовать")
    private val glossary = mapOf(
        "ru" to mapOf(
            "follow" to "Подписаться", "following" to "Подписки", "followers" to "Подписчики", "follow back" to "Подписаться в ответ",
            "unfollow" to "Отписаться", "friends" to "Друзья", "smols" to "Smols", "like" to "Нравится",
            "likes" to "Лайки", "share" to "Поделиться", "home" to "Главная", "inbox" to "Входящие", "profile" to "Профиль",
            "upload" to "Загрузить", "search" to "Поиск", "comments" to "Комментарии", "reply" to "Ответить",
            "log in" to "Войти", "sign up" to "Регистрация", "sign in" to "Войти", "log out" to "Выйти",
            "notifications" to "Уведомления", "settings" to "Настройки", "messages" to "Сообщения",
            "smolish" to "Смолиш", "download" to "Скачать", "post" to "Опубликовать", "edit profile" to "Редактировать профиль",
        ),
    )

    // names the translator leaves in latin or mangles, fixed inside whole sentences too
    private val names = mapOf("ru" to listOf(Regex("(?i)(?<![@/\\w])smolish(?!\\.?\\w)") to "Смолиш"))

    private fun fixNames(s: String, lang: String) =
        names[lang]?.fold(s) { acc, (re, to) -> re.replace(acc, to) } ?: s

    /** Translates [texts] from English; [done] gets null if nothing could. Runs off the main thread. */
    fun translate(texts: List<String>, lang: String, done: (List<String>?) -> Unit) {
        pool.execute {
            val out = arrayOfNulls<String>(texts.size)
            val todo = ArrayList<Int>()
            val words = glossary[lang]
            texts.forEachIndexed { i, t ->
                val hit = words?.get(t.trim().lowercase()) ?: cache.get("$lang\u0000$t")
                if (hit != null) out[i] = hit else todo += i
            }
            if (todo.isNotEmpty()) {
                val got = runCatching { yandex(todo.map { texts[it] }, lang) }.getOrNull()
                    ?: runCatching { google(todo.map { texts[it] }, lang) }.getOrNull()
                if (got == null) return@execute done(null)
                todo.forEachIndexed { k, i ->
                    out[i] = fixNames(got[k], lang)
                    cache.put("$lang\u0000${texts[i]}", out[i])
                }
            }
            done(out.map { it ?: "" })
        }
    }

    private fun yandex(texts: List<String>, lang: String): List<String>? {
        if (System.currentTimeMillis() < yandexDownUntil) return null
        val result = ArrayList<String>()
        // a few requests of up to ~6000 characters each
        var i = 0
        while (i < texts.size) {
            var j = i
            var chars = 0
            while (j < texts.size && j - i < 50 && (j == i || chars + texts[j].length < 6000)) { chars += texts[j].length; j++ }
            val part = yandexRequest(texts.subList(i, j), lang)
            if (part == null || part.size != j - i) {
                yandexDownUntil = System.currentTimeMillis() + 10 * 60_000 // let the fallback handle it for a while
                return null
            }
            result += part
            i = j
        }
        return result
    }

    private fun yandexRequest(texts: List<String>, lang: String): List<String>? {
        val c = URL("https://translate.yandex.net/api/v1/tr.json/translate?id=$sid-0-0&srv=android&lang=en-$lang")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { o -> o.write(texts.joinToString("&") { "text=" + URLEncoder.encode(it, "UTF-8") }.toByteArray()) }
        if (c.responseCode != 200) return null
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        if (j.optInt("code") != 200) return null
        val arr = j.optJSONArray("text") ?: return null
        return List(arr.length()) { arr.getString(it) }
    }

    /** The fallback: the endpoint google's own translate widgets use, one text per request. */
    private fun google(texts: List<String>, lang: String): List<String> = texts.map { text ->
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=${if (lang == "zh") "zh-CN" else lang}&dt=t&q=" + URLEncoder.encode(text, "UTF-8"))
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        if (c.responseCode != 200) error("http ${c.responseCode}")
        // [[["перевод","source",...], ...], ...] one piece per sentence
        val parts = JSONArray(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray(0)
        (0 until parts.length()).joinToString("") { parts.getJSONArray(it).optString(0) }
    }
}
