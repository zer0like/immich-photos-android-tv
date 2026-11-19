package com.immichclient.tv

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * App language, independent of the system language. The selection is stored in prefs ("settings"/"language", per device)
 * and applied via a Context with a custom Configuration ([wrap], in MainActivity.attachBaseContext); switching calls
 * `recreate()`. For strings outside Compose (pairing page, server-thread errors, row headers) [ctx] provides an
 * application Context in the selected language. Date/time patterns and number formatting follow [locale].
 *
 * Adding a language: add `values-xx/strings.xml` (all strings + plurals + date patterns), add its code to [LANGUAGES],
 * and add its native display name to [name]; nothing else needed.
 */
object Language {
    /** Supported languages (ISO 639-1), ordered by global speaker count (for both the picker and Settings). */
    val LANGUAGES = listOf("en", "zh", "hi", "es", "fr", "ru", "de", "ja", "ko", "nl")
    private const val PREFS = "settings"
    private const val KEY = "language"

    /** Native display name of the language (for the picker and Settings). */
    fun name(code: String) = when (code) {
        "nl" -> "Nederlands"; "de" -> "Deutsch"; "fr" -> "Français"; "es" -> "Español"; "ru" -> "Русский"; "zh" -> "中文"; "ja" -> "日本語"; "ko" -> "한국어"; "hi" -> "हिन्दी"; else -> "English"
    }

    private fun localeFor(c: String): Locale = if (c == "zh") Locale.SIMPLIFIED_CHINESE else Locale.forLanguageTag(c)

    @Volatile var code: String = "en"
        private set
    /** Whether a language has been chosen yet (otherwise show the first-run picker). */
    var chosen by mutableStateOf(false)
        private set
    /** Section to reopen once after a language/format change (`recreate`), e.g. Settings. */
    @Volatile var restartSection: Section? = null
    /** Row to restore focus onto after that restart ("language", "date", "clock"; see SettingsScreen). */
    @Volatile var focusRow: String? = null
    val locale: Locale get() = localeFor(code)

    /** Application context in the chosen language, for strings outside Compose. */
    lateinit var ctx: Context
        private set
    private lateinit var base: Context

    /** Language suggested by system settings (if supported), otherwise English. */
    fun systemLanguage(c: Context): String {
        val l = c.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
        return l.language.takeIf { it in LANGUAGES } ?: "en"
    }

    /** Called once at process startup (App.onCreate). */
    fun start(app: Context) {
        base = app.applicationContext
        val p = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString(KEY, null)?.takeIf { it in LANGUAGES }
        chosen = saved != null
        code = saved ?: systemLanguage(base)
        applyLocale()
    }

    /** Save choice and apply immediately; the activity must call `recreate()` afterwards. */
    fun choose(c: Context, newCode: String) {
        if (newCode !in LANGUAGES) return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newCode).apply()
        code = newCode
        chosen = true
        applyLocale()
    }

    private fun applyLocale() {
        Locale.setDefault(locale)
        ctx = wrap(base)
        DateTimeFmt.refresh()
    }

    /** Context configured for the chosen language (for Activity.attachBaseContext). */
    fun wrap(base: Context): Context = base.createConfigurationContext(configFor(base, locale))

    private fun configFor(base: Context, l: Locale) = Configuration(base.resources.configuration).apply { setLocale(l); setLayoutDirection(l) }

    /** Resources for another supported language (e.g. phone pairing page in the phone's language). */
    fun resources(lang: String): Resources =
        if (lang == code) ctx.resources else base.createConfigurationContext(configFor(base, localeFor(lang))).resources

    /** First supported language from an Accept-Language header ("nl-NL,nl;q=0.9,en;q=0.8"), or app language. */
    fun fromAcceptLanguage(header: String?): String {
        if (header.isNullOrBlank()) return code
        for (part in header.split(',')) {
            val tag = part.substringBefore(';').trim().lowercase(Locale.ROOT)
            val lang = tag.substringBefore('-')
            if (lang in LANGUAGES) return lang
        }
        return code
    }

    fun s(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String = ctx.resources.getQuantityString(id, n, *args)
    /** Integer formatted with locale grouping separators ("12.345" / "12,345" / "12 345"). */
    fun number(n: Int): String = DateTimeFmt.numberFmt.format(n.toLong())
    fun number(n: Long): String = DateTimeFmt.numberFmt.format(n)
}

/** Date/time formatting in the selected language; patterns live in string resources so translations can adapt them. */
object DateTimeFmt {
    /** Day/month order: default (follows language: "Sun 3 Sep 2023") or month first ("Sun, Sep 3, 2023"); per device. */
    enum class DateOrder { DEFAULT, MONTH_DAY }
    /** Clock style: follows language, 24-hour, or 12-hour (AM/PM); per device. */
    enum class ClockStyle { DEFAULT, H24, H12 }
    private const val PREFS = "settings"
    var dateOrder = DateOrder.DEFAULT; private set
    var clockStyle = ClockStyle.DEFAULT; private set

    /** Save choice and apply; the activity must call `recreate()` afterwards (same as switching language). */
    fun choose(c: Context, d: DateOrder = dateOrder, k: ClockStyle = clockStyle) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("date_format", d.name).putString("clock_format", k.name).apply()
        dateOrder = d; clockStyle = k
        refresh()
    }

    /** Patterns for "month first" (US style), independent of language. */
    private fun pattern(id: Int): String = when {
        dateOrder == DateOrder.MONTH_DAY && id == R.string.pattern_day_short -> "EEE, MMM d"
        dateOrder == DateOrder.MONTH_DAY && id == R.string.pattern_day_year -> "EEE, MMM d, yyyy"
        dateOrder == DateOrder.MONTH_DAY && id == R.string.pattern_day_long -> "EEEE, MMMM d, yyyy"
        clockStyle == ClockStyle.H24 && id == R.string.pattern_time -> "HH:mm"
        clockStyle == ClockStyle.H12 && id == R.string.pattern_time -> "h:mm a"
        else -> Language.s(id)
    }

    /** Sample output for Settings options without changing the active preference. */
    fun example(d: DateOrder? = null, k: ClockStyle? = null): String {
        val prev = dateOrder to clockStyle
        d?.let { dateOrder = it }; k?.let { clockStyle = it }
        val v = if (d != null) LocalDate.of(2023, 9, 3).format(DateTimeFormatter.ofPattern(pattern(R.string.pattern_day_year), Language.locale)).capitalizeFirst()
            else LocalDateTime.of(2023, 9, 3, 15, 30).format(DateTimeFormatter.ofPattern(pattern(R.string.pattern_time), Language.locale))
        dateOrder = prev.first; clockStyle = prev.second
        return v
    }

    lateinit var monthYear: DateTimeFormatter; private set
    lateinit var monthShort: DateTimeFormatter; private set
    lateinit var dayShort: DateTimeFormatter; private set
    lateinit var dayYear: DateTimeFormatter; private set
    lateinit var dayLong: DateTimeFormatter; private set
    lateinit var time: DateTimeFormatter; private set
    lateinit var numberFmt: NumberFormat; private set

    fun refresh() {
        val l = Language.locale
        runCatching {
            val p = Language.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            dateOrder = p.getString("date_format", null)?.let { n -> DateOrder.entries.firstOrNull { it.name == n } } ?: DateOrder.DEFAULT
            clockStyle = p.getString("clock_format", null)?.let { n -> ClockStyle.entries.firstOrNull { it.name == n } } ?: ClockStyle.DEFAULT
        }
        fun fmt(id: Int) = DateTimeFormatter.ofPattern(pattern(id), l)
        monthYear = fmt(R.string.pattern_month_year); monthShort = fmt(R.string.pattern_month_short)
        dayShort = fmt(R.string.pattern_day_short); dayYear = fmt(R.string.pattern_day_year); dayLong = fmt(R.string.pattern_day_long)
        time = fmt(R.string.pattern_time)
        numberFmt = NumberFormat.getIntegerInstance(l)
    }
}

private fun String.capitalizeFirst() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Language.locale) else it.toString() }

fun monthTitle(key: String): String =
    runCatching { LocalDate.parse(key.take(10)).format(DateTimeFmt.monthYear).capitalizeFirst() }.getOrDefault(key)

fun dayTitle(d: LocalDate, withYear: Boolean): String {
    val today = LocalDate.now()
    return when (d) {
        today -> Language.s(R.string.today)
        today.minusDays(1) -> Language.s(R.string.yesterday)
        else -> d.format(if (withYear || d.year != today.year) DateTimeFmt.dayYear else DateTimeFmt.dayShort).capitalizeFirst()
    }
}

fun monthYear(t: LocalDateTime): String = t.format(DateTimeFmt.monthYear)

fun longDate(t: LocalDateTime): String = t.format(DateTimeFmt.dayLong).capitalizeFirst() + " ·  " + t.format(DateTimeFmt.time)

fun yearsAgo(n: Int) = Language.plural(R.plurals.years_ago, n, n)

/**
 * Country names: Immich returns them in English. Maps via ISO code (Locale.getISOCountries + English name)
 * to the localized country name in the active app language; unknown names are left untouched.
 */
object CountryName {
    private val isoByEnglish: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (iso in Locale.getISOCountries()) {
            val n = Locale("", iso).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
            m[n] = iso
            // ICU uses "Bosnia & Herzegovina", "St. Lucia"; Immich's geocoder uses "Bosnia and Herzegovina", "Saint Lucia"
            m[n.replace(" & ", " and ")] = iso
            if (n.startsWith("st. ")) m["saint " + n.removePrefix("st. ")] = iso
        }
        // Names Immich's geocoder formats differently from ICU
        m["the netherlands"] = "NL"; m["netherlands"] = "NL"; m["united states of america"] = "US"; m["united states"] = "US"
        m["czech republic"] = "CZ"; m["czechia"] = "CZ"; m["russia"] = "RU"; m["south korea"] = "KR"; m["north korea"] = "KP"
        m["vietnam"] = "VN"; m["macedonia"] = "MK"; m["north macedonia"] = "MK"; m["ivory coast"] = "CI"; m["côte d'ivoire"] = "CI"
        m["swaziland"] = "SZ"; m["eswatini"] = "SZ"; m["turkey"] = "TR"; m["türkiye"] = "TR"; m["uk"] = "GB"; m["great britain"] = "GB"
        m["cape verde"] = "CV"; m["myanmar (burma)"] = "MM"; m["burma"] = "MM"; m["the bahamas"] = "BS"; m["the gambia"] = "GM"
        m["bolivia"] = "BO"; m["tanzania"] = "TZ"; m["laos"] = "LA"; m["syria"] = "SY"; m["iran"] = "IR"; m["moldova"] = "MD"
        m["brunei"] = "BN"; m["micronesia"] = "FM"; m["palestine"] = "PS"; m["vatican city"] = "VA"; m["hong kong"] = "HK"; m["macau"] = "MO"
        m
    }
    private val cache = HashMap<String, String>()
    private var cacheLang = ""

    /** English name (as returned by Immich) -> ISO 3166 code, or null if unknown. */
    fun iso(english: String): String? = isoByEnglish[english.trim().lowercase(Locale.ROOT)]

    /** Localized country name for an ISO code in the app language. */
    fun fromIso(iso: String): String = Locale("", iso).getDisplayCountry(Language.locale).ifBlank { iso }

    /** English name -> localized name in the app language (cached per language). */
    @Synchronized fun translate(english: String): String {
        if (cacheLang != Language.code) { cache.clear(); cacheLang = Language.code }
        return cache.getOrPut(english) {
            val iso = isoByEnglish[english.trim().lowercase(Locale.ROOT)] ?: return@getOrPut english
            Locale("", iso).getDisplayCountry(Language.locale).ifBlank { english }
        }
    }
}
