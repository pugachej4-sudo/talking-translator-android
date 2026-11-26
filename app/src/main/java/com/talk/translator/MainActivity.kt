package com.talk.translator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    class Lang(val name: String, val ml: String, val tag: String)

    private val langs = listOf(
        Lang("русский", TranslateLanguage.RUSSIAN, "ru-RU"),
        Lang("английский", TranslateLanguage.ENGLISH, "en-US"),
        Lang("немецкий", TranslateLanguage.GERMAN, "de-DE"),
        Lang("французский", TranslateLanguage.FRENCH, "fr-FR"),
        Lang("испанский", TranslateLanguage.SPANISH, "es-ES"),
        Lang("итальянский", TranslateLanguage.ITALIAN, "it-IT"),
        Lang("португальский", TranslateLanguage.PORTUGUESE, "pt-BR"),
        Lang("польский", TranslateLanguage.POLISH, "pl-PL"),
        Lang("украинский", TranslateLanguage.UKRAINIAN, "uk-UA"),
        Lang("турецкий", TranslateLanguage.TURKISH, "tr-TR"),
        Lang("китайский", TranslateLanguage.CHINESE, "zh-CN"),
        Lang("японский", TranslateLanguage.JAPANESE, "ja-JP"),
        Lang("корейский", TranslateLanguage.KOREAN, "ko-KR"),
        Lang("арабский", TranslateLanguage.ARABIC, "ar-SA")
    )
    private val TEAL = Color.parseColor("#009688")
    private val DARK = Color.parseColor("#00796B")
    private val LIGHT = Color.parseColor("#B2E5DD")

    private var online = true
    private var topText = ""
    private var botText = ""
    private var listeningSide = -1
    private var pendingSide = -1
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private lateinit var topTv: TextView
    private lateinit var botTv: TextView
    private lateinit var micTop: TextView
    private lateinit var micBot: TextView
    private lateinit var spSrc: Spinner
    private lateinit var spDst: Spinner

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun src() = langs[spSrc.selectedItemPosition]
    private fun dst() = langs[spDst.selectedItemPosition]

    private fun circle(label: String, bg: Int, size: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(12), dp(6), dp(12), dp(6)) }
        setOnClickListener { onClick() }
    }

    private fun icon(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; setTextColor(color)
        setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { onClick() }
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Color.WHITE) }

        // ---- верхняя половина: перевёрнута на 180° для собеседника ----
        micTop = circle("🎙", DARK, 64) { listen(1) }
        topTv = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "Собеседник…"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val topRow = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(icon("🔊", Color.WHITE) { speak(topText, dst()) })
            addView(icon("⧉", Color.WHITE) { copy(topText) })
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(TEAL); rotation = 180f; setPadding(dp(16), dp(16), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(micTop); addView(topTv); addView(topRow)
        }

        // ---- нижняя половина ----
        val tools = LinearLayout(this).apply {
            addView(icon("★", TEAL) { fav() }); addView(spacer())
            addView(icon("🔊", TEAL) { speak(botText, src()) })
            addView(icon("⧉", TEAL) { copy(botText) })
            addView(icon("⋮", Color.DKGRAY) { }.also { v -> v.setOnClickListener { menu(v) } })
        }
        botTv = TextView(this).apply {
            textSize = 20f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; text = "Нажмите микрофон"
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        micBot = circle("🎙", TEAL, 76) { listen(0) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(circle("🕘", LIGHT, 52) { showHist(false) })
            addView(micBot)
            addView(circle("✕", Color.LTGRAY, 52) { clearAll() })
        }
        val names = langs.map { it.name }
        spSrc = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names); setSelection(0) }
        spDst = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names); setSelection(1) }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val langRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(12))
            addView(spSrc, lp())
            addView(icon("⇄", Color.DKGRAY) { swap() })
            addView(spDst, lp())
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(tools); addView(botTv); addView(bar); addView(langRow)
        }
        root.addView(top); root.addView(bottom)
        setContentView(root)
    }

    // ---------- распознавание ----------
    private fun listen(side: Int) {
        if (listeningSide == side) { rec?.stopListening(); return }
        if (listeningSide != -1) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSide = side
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            botTv.text = "Нет службы распознавания речи. Установите приложение «Google»."; return
        }
        val a = if (side == 0) src() else dst()
        val b = if (side == 0) dst() else src()
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    if (side == 0) botTv.text = t else topTv.text = t
                }
                override fun onError(code: Int) {
                    idle()
                    botTv.text = "Ошибка распознавания ($code). Для офлайна скачайте речевой пакет: Настройки телефона → Google → Голосовой ввод → Офлайн-распознавание."
                }
                override fun onResults(r: Bundle?) {
                    idle()
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) { botTv.text = "Речь не распознана"; return }
                    translate(side, t, a, b)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
        listeningSide = side
        (if (side == 0) micBot else micTop).text = "⏹"
        botTv.text = "Слушаю…"
        rec?.startListening(i)
    }

    private fun idle() { listeningSide = -1; micBot.text = "🎙"; micTop.text = "🎙" }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED && pendingSide != -1) listen(pendingSide)
        pendingSide = -1
    }

    // ---------- перевод ----------
    private fun translate(side: Int, text: String, a: Lang, b: Lang) {
        botTv.text = "Перевожу…"
        val t = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(a.ml).setTargetLanguage(b.ml).build())
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { res -> show(side, text, res, a, b); speak(res, b) }
                    .addOnFailureListener { botTv.text = "Ошибка перевода: ${it.message}" }
            }
            .addOnFailureListener {
                botTv.text = "Нет языковой модели. Подключите интернет и выберите Меню → Скачать языки."
            }
    }

    private fun show(side: Int, text: String, res: String, a: Lang, b: Lang) {
        if (side == 0) { botText = text; topText = res } else { topText = text; botText = res }
        botTv.text = botText; topTv.text = topText
        val h = loadHist()
        h.add(0, JSONObject().put("src", a.name).put("dst", b.name).put("text", text).put("tr", res)
            .put("fav", false).put("time", SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())))
        saveHist(h)
    }

    // ---------- озвучка ----------
    private fun speak(text: String, l: Lang) {
        if (text.isBlank()) return
        val t = tts ?: return
        val loc = Locale.forLanguageTag(l.tag)
        t.language = loc
        if (!online) {
            t.voices?.firstOrNull { it.locale.language == loc.language && !it.isNetworkConnectionRequired }
                ?.let { t.voice = it }
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    // ---------- меню, история ----------
    private fun menu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (online) "Режим: ОНЛАЙН (нажмите — офлайн)" else "Режим: ОФЛАЙН (нажмите — онлайн)")
            menu.add(0, 2, 1, "История")
            menu.add(0, 3, 2, "Избранное")
            menu.add(0, 4, 3, "Скачать языки для офлайна")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { online = !online; Toast.makeText(this@MainActivity, if (online) "Онлайн" else "Офлайн", Toast.LENGTH_SHORT).show() }
                    2 -> showHist(false)
                    3 -> showHist(true)
                    4 -> downloadLangs()
                }
                true
            }
            show()
        }
    }

    private fun downloadLangs() {
        val codes = setOf(src().ml, dst().ml)
        var left = codes.size
        botTv.text = "Скачиваю языки…"
        codes.forEach { c ->
            RemoteModelManager.getInstance()
                .download(TranslateRemoteModel.Builder(c).build(), DownloadConditions.Builder().build())
                .addOnSuccessListener { if (--left == 0) botTv.text = "Языки скачаны ✓ Теперь работает без интернета." }
                .addOnFailureListener { botTv.text = "Ошибка загрузки. Нужен интернет." }
        }
    }

    private fun loadHist(): MutableList<JSONObject> {
        val arr = JSONArray(getSharedPreferences("tt", MODE_PRIVATE).getString("h", "[]"))
        return MutableList(arr.length()) { arr.getJSONObject(it) }
    }

    private fun saveHist(l: List<JSONObject>) {
        getSharedPreferences("tt", MODE_PRIVATE).edit().putString("h", JSONArray(l).toString()).apply()
    }

    private fun fav() {
        val h = loadHist(); if (h.isEmpty()) return
        val f = !h[0].optBoolean("fav"); h[0].put("fav", f); saveHist(h)
        Toast.makeText(this, if (f) "★ В избранном" else "Убрано из избранного", Toast.LENGTH_SHORT).show()
    }

    private fun showHist(onlyFav: Boolean) {
        val items = loadHist().filter { !onlyFav || it.optBoolean("fav") }
        if (items.isEmpty()) { Toast.makeText(this, "Пусто", Toast.LENGTH_SHORT).show(); return }
        val labels = items.map { (if (it.optBoolean("fav")) "★ " else "") + it.getString("time") + "\n" + it.getString("text") + "  →  " + it.getString("tr") }
        AlertDialog.Builder(this).setTitle(if (onlyFav) "Избранное" else "История")
            .setItems(labels.toTypedArray()) { _, i ->
                val h = items[i]
                spSrc.setSelection(langs.indexOfFirst { it.name == h.getString("src") })
                spDst.setSelection(langs.indexOfFirst { it.name == h.getString("dst") })
                botText = h.getString("text"); topText = h.getString("tr")
                botTv.text = botText; topTv.text = topText
            }.show()
    }

    private fun swap() {
        val a = spSrc.selectedItemPosition; spSrc.setSelection(spDst.selectedItemPosition); spDst.setSelection(a)
    }

    private fun clearAll() { topText = ""; botText = ""; topTv.text = "Собеседник…"; botTv.text = "Нажмите микрофон" }

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", s))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() { rec?.destroy(); tts?.shutdown(); super.onDestroy() }
}
