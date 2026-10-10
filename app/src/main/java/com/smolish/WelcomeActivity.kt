package com.smolish

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormatSymbols
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.util.Locale

/**
 * First launch: Smol drops in, waves and walks you through language, birthday, where you
 * celebrate, translation and notifications. Everything after the language question is written
 * in English and translated by Yandex (in one go) into the language you pick.
 */
class WelcomeActivity : ComponentActivity() {

    private lateinit var smol: SmolView
    private lateinit var bubble: TextView
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var typing: Runnable? = null
    private var afterTyping: (() -> Unit)? = null

    private var lang = "en"
    private val tr = HashMap<String, String>()
    private fun t(s: String) = tr[s] ?: s

    private val regions by lazy {
        listOf(
            Icons.RUSSIA to getString(R.string.region_ru), Icons.WESTERN to getString(R.string.region_west),
            Icons.USA to getString(R.string.region_us), Icons.CANADA to getString(R.string.region_ca),
            Icons.MUSLIM to getString(R.string.region_muslim), Icons.NO_RELIGION to getString(R.string.region_none),
        )
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { done() }

    companion object {
        const val HELLO = "Heya, I'm Smol. I'm here to guide you through Smolish. First, what's your language?"
        const val BIRTHDAY = "Okay, I need to get your birthday to congratulate you when it comes!"
        const val NO_BIRTHDAY = "I'd rather not say"
        const val NEXT = "Next"
        const val BIRTHDAY_TODAY = "Wait... that's TODAY?! Happy birthday!!"
        const val BIRTHDAY_SECRET = "That's okay, it stays a secret."
        const val REGION = "Got it! Now, where do you celebrate? I dress up for holidays, so I need to know which ones are yours."
        const val TRANSLATE = "Smolish itself only speaks English. Want me to translate it for you? You can long-press any text to see the original."
        const val TRANSLATE_YES = "Yes, translate it"
        const val TRANSLATE_NO = "No, keep English"
        const val NOTIFS = "Want me to tell you when someone likes, comments or follows you? I'll only knock when something happens."
        const val NOTIFS_YES = "Sure"
        const val NOTIFS_NO = "Not now"
        const val DONE = "You're all set! Go watch some smols. If you ever need me, long-press the Smolish icon and open Settings."
        const val GO = "Let's go!"
        const val OFFLINE = "Hmm, I can't reach the translator right now, so let's stick to English for a bit."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.welcome_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        smol = findViewById(R.id.smol)
        bubble = findViewById(R.id.bubble)
        content = findViewById(R.id.content)
        smol.theme = Icons.wanted(this)
        bubble.alpha = 0f
        bubble.setOnClickListener { skipTyping() }

        // let him land and start waving before he talks
        handler.postDelayed({
            bubble.animate().alpha(1f).setDuration(200).start()
            say(HELLO, SmolView.Mood.WAVE) { askLanguage() }
        }, 700)
    }

    // --- the speech bubble types its text out; tap it to skip ahead
    private fun say(text: String, mood: SmolView.Mood = SmolView.Mood.TALK, then: () -> Unit = {}) {
        typing?.let { handler.removeCallbacks(it) }
        content.removeAllViews()
        smol.mood = mood
        smol.talking = true
        fullText = text
        afterTyping = then
        var i = 0
        typing = object : Runnable {
            override fun run() {
                i++
                bubble.text = text.substring(0, i)
                if (i < text.length) handler.postDelayed(this, if (text[i - 1] in ".!?") 160 else 24)
                else finishTyping()
            }
        }
        if (text.isEmpty()) finishTyping() else handler.post(typing!!)
    }

    private var fullText = ""

    private fun skipTyping() {
        val r = typing ?: return
        handler.removeCallbacks(r)
        bubble.text = fullText
        finishTyping()
    }

    private fun finishTyping() {
        typing = null
        smol.talking = false
        afterTyping?.let { afterTyping = null; it() }
    }

    /** Shows the step's controls, fading in under the bubble. */
    private fun show(vararg views: View) {
        content.removeAllViews()
        views.forEach { v -> content.addView(v); v.alpha = 0f; v.animate().alpha(1f).setDuration(220).start() }
    }

    private fun choice(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.text))
        textSize = 16f
        setBackgroundResource(R.drawable.choice)
        val p = (14 * resources.displayMetrics.density).toInt()
        setPadding(p + p / 2, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = p / 2 }
        setOnClickListener { onClick() }
    }

    private fun primary(text: String, onClick: () -> Unit) = choice(text, onClick).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = null
        setBackgroundColor(ContextCompat.getColor(context, R.color.brand))
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 14 * resources.displayMetrics.density)
        }
        (layoutParams as LinearLayout.LayoutParams).topMargin = (20 * resources.displayMetrics.density).toInt()
    }

    // --- 1. language
    private fun askLanguage() {
        val langs = listOf("en") + Translator.LANGS
        val first = Locale.getDefault().language.takeIf { it in langs }
        val ordered = if (first != null && first != "en") listOf(first) + (langs - first) else langs
        show(*ordered.map { code -> choice(Translator.name(code)) { pickLanguage(code) } }.toTypedArray())
    }

    private fun pickLanguage(code: String) {
        lang = code
        if (code == "en") return askBirthday()
        if (code in Translator.LANGS) Translator.setLang(this, code)
        content.removeAllViews()
        smol.mood = SmolView.Mood.THINK
        bubble.text = "…"
        val texts = listOf(BIRTHDAY, NO_BIRTHDAY, NEXT, BIRTHDAY_TODAY, BIRTHDAY_SECRET, REGION, TRANSLATE, TRANSLATE_YES,
            TRANSLATE_NO, NOTIFS, NOTIFS_YES, NOTIFS_NO, DONE, GO) + regions.map { it.second }
        Translator.translate(texts, code) { out ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (out == null) {
                    lang = "en"
                    say(OFFLINE) { handler.postDelayed({ askBirthday() }, 900) }
                } else {
                    texts.forEachIndexed { i, s -> if (out[i].isNotBlank()) tr[s] = out[i] }
                    askBirthday()
                }
            }
        }
    }

    // --- 2. birthday
    private fun askBirthday() {
        say(t(BIRTHDAY)) {
            val dp = resources.displayMetrics.density
            val month = NumberPicker(this).apply {
                minValue = 1; maxValue = 12
                displayedValues = DateFormatSymbols.getInstance(Locale(lang)).months.take(12).toTypedArray()
                value = Icons.birthday(this@WelcomeActivity)?.monthValue ?: LocalDate.now().monthValue
            }
            val day = NumberPicker(this).apply {
                minValue = 1; maxValue = Month.of(month.value).maxLength() // feb 29 is fine, there's no year
                value = Icons.birthday(this@WelcomeActivity)?.dayOfMonth ?: 1
            }
            month.setOnValueChangedListener { _, _, m -> day.maxValue = Month.of(m).maxLength() }
            val pickers = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(day, LinearLayout.LayoutParams((90 * dp).toInt(), -2))
                addView(month, LinearLayout.LayoutParams((160 * dp).toInt(), -2))
            }
            val none = CheckBox(this).apply {
                text = t(NO_BIRTHDAY)
                setTextColor(ContextCompat.getColor(context, R.color.text))
                buttonTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand))
                setOnCheckedChangeListener { _, off -> pickers.alpha = if (off) 0.35f else 1f; day.isEnabled = !off; month.isEnabled = !off }
            }
            show(pickers, none, primary(t(NEXT)) {
                val md = if (none.isChecked) null else MonthDay.of(month.value, day.value)
                Icons.setBirthday(this, md)
                when {
                    md == null -> say(t(BIRTHDAY_SECRET)) { handler.postDelayed({ askRegion() }, 700) }
                    md == MonthDay.now() -> {
                        smol.theme = "birthday"
                        say(t(BIRTHDAY_TODAY), SmolView.Mood.HAPPY) { handler.postDelayed({ askRegion() }, 1200) }
                    }
                    else -> askRegion()
                }
            })
        }
    }

    // --- 3. where you celebrate
    private fun askRegion() {
        say(t(REGION)) {
            show(*regions.map { (key, label) ->
                choice(t(label)) {
                    Icons.setRegion(this, key)
                    askTranslate()
                }
            }.toTypedArray())
        }
    }

    // --- 4. translate the site (only if you didn't pick english)
    private fun askTranslate() {
        if (lang == "en" || lang !in Translator.LANGS) return askNotifications()
        say(t(TRANSLATE)) {
            show(
                primary(t(TRANSLATE_YES)) { Translator.setOn(this, true); askNotifications() },
                choice(t(TRANSLATE_NO)) { Translator.setOn(this, false); askNotifications() },
            )
        }
    }

    // --- 5. notifications (android 13+, if not allowed yet)
    private fun askNotifications() {
        val need = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!need) return done()
        getSharedPreferences("app", 0).edit().putBoolean("asked_notifications", true).apply() // the app won't ask again
        say(t(NOTIFS)) {
            show(
                primary(t(NOTIFS_YES)) { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                choice(t(NOTIFS_NO)) { done() },
            )
        }
    }

    // --- 6. bye
    private fun done() {
        Icons.setSetupDone(this)
        say(t(DONE), SmolView.Mood.HAPPY) {
            show(primary(t(GO)) { finish() })
        }
    }

    // leaving = a good moment to switch the icon if today is a special day
    override fun onStop() {
        super.onStop()
        if (Icons.setupDone(this)) Icons.apply(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
