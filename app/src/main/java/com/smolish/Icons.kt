package com.smolish

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAdjusters
import java.time.MonthDay

/**
 * Seasonal launcher icons. Each icon is an <activity-alias> of MainActivity with its own icon;
 * exactly one of them is enabled at a time, and that's what the launcher shows.
 */
object Icons {
    const val AUTO = "auto"
    val KEYS = listOf("default", "halloween", "winter", "valentine", "aprilfools", "easter", "birthday",
        "thanksgiving", "ramadan", "eid")

    // where you celebrate: decides which holidays show up and on which dates
    const val RUSSIA = "ru"        // orthodox easter, new year dec 20 - jan 14 (covers orthodox christmas jan 7)
    const val WESTERN = "west"     // europe: western easter, christmas dec 10 - jan 6
    const val USA = "us"           // like europe + thanksgiving (4th thursday of november)
    const val CANADA = "ca"        // like europe + thanksgiving (2nd monday of october)
    const val MUSLIM = "muslim"    // ramadan, eid al-fitr, eid al-adha, new year
    const val NO_RELIGION = "none" // no easter, still new year / christmas

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", 0)

    fun mode(ctx: Context) = prefs(ctx).getString("icon", AUTO) ?: AUTO
    fun setMode(ctx: Context, mode: String) = prefs(ctx).edit().putString("icon", mode).apply()
    fun region(ctx: Context) = prefs(ctx).getString("region", RUSSIA) ?: RUSSIA
    fun setRegion(ctx: Context, r: String) = prefs(ctx).edit().putString("region", r).apply()

    /** Birthday without a year, or null. */
    fun birthday(ctx: Context): MonthDay? =
        prefs(ctx).getString("birthday", null)?.let { runCatching { MonthDay.parse("--$it") }.getOrNull() }
    fun setBirthday(ctx: Context, md: MonthDay?) =
        prefs(ctx).edit().putString("birthday", md?.toString()?.removePrefix("--")).apply()

    fun setupDone(ctx: Context) = prefs(ctx).getBoolean("setup_done", false)
    fun setSetupDone(ctx: Context) = prefs(ctx).edit().putBoolean("setup_done", true).apply()

    /** Which icon a date gets. Birthday beats everything, then april fools, then the holidays. */
    fun forDate(d: LocalDate, region: String, birthday: MonthDay?): String {
        val md = MonthDay.from(d)
        fun within(from: MonthDay, to: MonthDay) =
            if (from <= to) md >= from && md <= to else md >= from || md <= to // ranges over new year
        if (birthday != null && md == birthday) return "birthday"
        if (md == MonthDay.of(4, 1)) return "aprilfools"
        if (region == MUSLIM) {
            // umm al-qura islamic calendar (built into java.time, the same one saudi arabia uses)
            val h = HijrahDate.from(d)
            val hm = h.get(ChronoField.MONTH_OF_YEAR)
            val hd = h.get(ChronoField.DAY_OF_MONTH)
            if (hm == 10 && hd <= 3) return "eid"                  // eid al-fitr
            if (hm == 12 && hd in 10..13) return "eid"             // eid al-adha
        }
        thanksgiving(d.year, region)?.let { (from, to) -> if (!d.isBefore(from) && !d.isAfter(to)) return "thanksgiving" }
        easter(d.year, region)?.let { e ->
            if (!d.isBefore(e.minusDays(3)) && !d.isAfter(e.plusDays(1))) return "easter"
        }
        if (within(MonthDay.of(10, 24), MonthDay.of(11, 1))) return "halloween"
        if (within(MonthDay.of(2, 10), MonthDay.of(2, 15))) return "valentine"
        if (region == MUSLIM && HijrahDate.from(d).get(ChronoField.MONTH_OF_YEAR) == 9) return "ramadan"
        val winter = when (region) {
            WESTERN, USA, CANADA -> within(MonthDay.of(12, 10), MonthDay.of(1, 6))
            NO_RELIGION -> within(MonthDay.of(12, 20), MonthDay.of(1, 8))
            MUSLIM -> within(MonthDay.of(12, 29), MonthDay.of(1, 2)) // just new year
            else -> within(MonthDay.of(12, 20), MonthDay.of(1, 14))
        }
        return if (winter) "winter" else "default"
    }

    /** The days the thanksgiving icon shows, or null when the region doesn't have it. */
    fun thanksgiving(y: Int, region: String): Pair<LocalDate, LocalDate>? = when (region) {
        USA -> LocalDate.of(y, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)).let { it.minusDays(1) to it.plusDays(2) }
        CANADA -> LocalDate.of(y, 10, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.MONDAY)).let { it.minusDays(2) to it }
        else -> null
    }

    /** Easter sunday for the region's church, or null when it isn't celebrated. */
    fun easter(y: Int, region: String): LocalDate? = when (region) {
        WESTERN, USA, CANADA -> { // anonymous gregorian algorithm
            val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
            val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451; val n = h + l - 7 * m + 114
            LocalDate.of(y, n / 31, n % 31 + 1)
        }
        RUSSIA -> { // meeus julian algorithm, then julian -> gregorian (+13 days, fine until 2099)
            val a = y % 4; val b = y % 7; val c = y % 19
            val d = (19 * c + 15) % 30; val e = (2 * a + 4 * b - d + 34) % 7; val n = d + e + 114
            LocalDate.of(y, n / 31, n % 31 + 1).plusDays(13)
        }
        else -> null
    }

    fun wanted(ctx: Context): String {
        val m = mode(ctx)
        return if (m != AUTO && m in KEYS) m else forDate(LocalDate.now(), region(ctx), birthday(ctx))
    }

    private fun alias(ctx: Context, key: String) =
        ComponentName(ctx, "com.smolish.Launcher" + if (key == "default") "" else key.replaceFirstChar { it.uppercase() })

    /**
     * Switches the launcher icon if it should change. Called when leaving the app, because some
     * launchers restart the app while the icon swaps.
     */
    fun apply(ctx: Context) {
        val want = wanted(ctx)
        val pm = ctx.packageManager
        val wantComp = alias(ctx, want)
        val current = pm.getComponentEnabledSetting(wantComp)
        val wantDefault = want == "default"
        // default alias is enabled in the manifest, the others are disabled
        val isOn = current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && wantDefault)
        if (isOn && KEYS.all { it == want || !enabled(ctx, it) }) return
        // turn the new one on first, so there's never a moment with no launcher icon
        pm.setComponentEnabledSetting(wantComp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (k in KEYS) if (k != want) {
            pm.setComponentEnabledSetting(alias(ctx, k), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    private fun enabled(ctx: Context, key: String): Boolean = when (ctx.packageManager.getComponentEnabledSetting(alias(ctx, key))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> key == "default"
        else -> false
    }
}
