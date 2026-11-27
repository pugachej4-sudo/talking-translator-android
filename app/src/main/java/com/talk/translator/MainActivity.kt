package com.talk.translator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

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

    private var mode = 2                 // 0 — онлайн, 1 — офлайн, 2 — авто
    private var autoForceOffline = false
    private val online: Boolean
        get() = when (mode) { 0 -> true; 1 -> false; else -> hasNet() && !autoForceOffline }
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
    private lateinit var pb: ProgressBar

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
        mode = getSharedPreferences("tt", MODE_PRIVATE).getInt("mode", 2)
        val inf = IntentFilter(ACTION_INSTALL)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, inf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, inf)
        initTts("com.google.android.tts")

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
        spSrc = makeSpinner(names, 0)
        spDst = makeSpinner(names, 1)
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val langRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(12))
            addView(spSrc, lp())
            addView(icon("⇄", Color.DKGRAY) { swap() })
            addView(spDst, lp())
        }
        pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(32), 0, dp(32), dp(8)) }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(tools); addView(botTv); addView(pb); addView(bar); addView(langRow)
        }
        root.addView(top); root.addView(bottom)
        setContentView(root)
        root.post { runSetup(); autoMaintenance() }
    }

    // ---------- распознавание ----------
    private var retryLeft = 1

    private fun listen(side: Int, retry: Boolean = false) {
        if (listeningSide == side) { if (vosk != null) vosk?.stop() else rec?.stopListening(); return }
        if (listeningSide != -1) return
        if (!retry) retryLeft = 1
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSide = side
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        val a0 = if (side == 0) src() else dst()
        val b0 = if (side == 0) dst() else src()
        if (!online && voskReady(a0)) { listenVosk(side, a0, b0); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            botTv.text = "Нет службы распознавания речи. Установите приложение «Google»."; return
        }
        val a = if (side == 0) src() else dst()
        val b = if (side == 0) dst() else src()
        if (getSharedPreferences("tt", MODE_PRIVATE).getBoolean("sys", false)) {
            dlgSide = side; dlgA = a; dlgB = b
            val di = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.tag)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите: ${a.name}")
            try { startActivityForResult(di, 3) } catch (e: Exception) { botTv.text = "Нет системного голосового ввода." }
            return
        }
        rec?.destroy()
        rec = newRecognizer().apply {
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
                    if (code == 12 || code == 13) {   // язык не поддерживается / пакет не скачан
                        ensureSpeech(a, true)
                        return
                    }
                    if ((code == 1 || code == 2) && mode == 2 && !autoForceOffline) {
                        autoForceOffline = true
                        ui.postDelayed({ autoForceOffline = false }, 60000)
                        botTv.text = "Нет интернета — переключаюсь на офлайн…"
                        ui.postDelayed({ if (listeningSide == -1) listen(side, true) }, 300)
                        return
                    }
                    if ((code == 7 || code == 6) && retryLeft > 0) {
                        retryLeft--
                        botTv.text = "Слушаю…"
                        ui.postDelayed({ if (listeningSide == -1) listen(side, true) }, 300)
                        return
                    }
                    if (code != 9 && code != 7 && code != 6) {
                        if (voskReady(a)) {
                            botTv.text = "Переключаюсь на офлайн-распознавание…"
                            ui.postDelayed({ if (listeningSide == -1) listenVosk(side, a, b) }, 200)
                            return
                        }
                        if (!useDefaultRec) {
                            useDefaultRec = true
                            botTv.text = "Пробую другую службу распознавания…"
                            ui.postDelayed({ if (listeningSide == -1) listen(side, true) }, 300)
                            return
                        }
                    }
                    failCount++
                    val sp = getSharedPreferences("tt", MODE_PRIVATE)
                    if (failCount >= 2 && !sp.getBoolean("sys", false)) {
                        sp.edit().putBoolean("sys", true).apply()
                        botTv.text = "Встроенное распознавание не сработало ($code). Включил системное окно голосового ввода — нажмите микрофон ещё раз."
                    } else botTv.text = errText(code)
                }
                override fun onResults(r: Bundle?) {
                    idle(); failCount = 0
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
        if (c == 2) { runSetup(); return }
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED && pendingSide != -1) listen(pendingSide)
        pendingSide = -1
    }

    // ---------- Samsung: принудительно используем службы Google ----------
    private var useDefaultRec = false

    private fun newRecognizer(): SpeechRecognizer {
        if (useDefaultRec) return SpeechRecognizer.createSpeechRecognizer(this)
        val cn = ComponentName("com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        return try {
            val found = packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE).setComponent(cn), 0).isNotEmpty()
            if (found) SpeechRecognizer.createSpeechRecognizer(this, cn)
            else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) { SpeechRecognizer.createSpeechRecognizer(this) }
    }

    private fun initTts(engine: String?) {
        ttsEngine = engine
        val cb = TextToSpeech.OnInitListener { st ->
            if (st == TextToSpeech.SUCCESS) { ttsReady = true; runSetup() }
            else if (engine != null) initTts(null)   // Google TTS нет — берём системный
        }
        tts = if (engine != null) TextToSpeech(this, cb, engine) else TextToSpeech(this, cb)
    }

    // ---------- автозагрузка пакетов при выборе языка ----------
    private val prepared = HashSet<String>()
    private val asked = HashSet<String>()
    private val helpers = ArrayList<SpeechRecognizer>()
    private var ttsReady = false

    private fun makeSpinner(names: List<String>, sel: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
        setSelection(sel)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { ensureLang(langs[pos]) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun ensureLang(l: Lang) { ensureModel(l); ensureSpeech(l, false); ensureTts(l) }

    // 1) модель перевода (ML Kit)
    private fun ensureModel(l: Lang) {
        if (l.ml == TranslateLanguage.ENGLISH) return
        val mm = RemoteModelManager.getInstance()
        val m = TranslateRemoteModel.Builder(l.ml).build()
        mm.isModelDownloaded(m).addOnSuccessListener { ok ->
            if (!ok) {
                downloadModel(l)
            }
        }
    }

    // 2) офлайн-распознавание речи: собственные модели Vosk (не зависят от Google)
    private val voskNames = mapOf(
        "ru" to "vosk-model-small-ru-0.22", "en" to "vosk-model-small-en-us-0.15",
        "es" to "vosk-model-small-es-0.42", "de" to "vosk-model-small-de-0.15",
        "fr" to "vosk-model-small-fr-0.22", "it" to "vosk-model-small-it-0.22",
        "pt" to "vosk-model-small-pt-0.3", "pl" to "vosk-model-small-pl-0.22",
        "uk" to "vosk-model-small-uk-v3-small", "tr" to "vosk-model-small-tr-0.3",
        "zh" to "vosk-model-small-cn-0.22", "ja" to "vosk-model-small-ja-0.22",
        "ko" to "vosk-model-small-ko-0.22")

    private fun voskKey(l: Lang) = l.tag.substringBefore('-')
    private fun voskUrl(l: Lang): String? = voskNames[voskKey(l)]?.let { "https://alphacephei.com/vosk/models/$it.zip" }
    private fun voskDir(l: Lang) = File(filesDir, "vosk/" + voskKey(l))
    private fun voskReady(l: Lang) = File(voskDir(l), ".ok").exists()
    private fun voskPath(l: Lang): String? {
        val d = voskDir(l)
        if (File(d, "am").isDirectory) return d.absolutePath
        return d.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").isDirectory }?.absolutePath
    }

    private fun ensureSpeech(l: Lang, force: Boolean) {
        if (voskUrl(l) == null || voskReady(l) || tasks.containsKey("Речевой пакет: ${l.name}")) return
        if (!hasNet()) return
        if (force) { downloadVosk(l); return }
        if (!prepared.add("v:" + l.tag)) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!cm.isActiveNetworkMetered) downloadVosk(l)
        else ask("vm:" + l.tag, "Речевой пакет для офлайна",
            "Скачать офлайн-распознавание речи для языка «${l.name}» (около 50 МБ) по мобильному интернету?",
            "Да, скачать") { downloadVosk(l) }
    }

    private fun downloadVosk(l: Lang) {
        val url = voskUrl(l) ?: return
        val k = "Речевой пакет: ${l.name}"
        if (tasks.containsKey(k)) return
        taskStart(k, 0, 1800000)
        thread {
            try {
                val zip = File(cacheDir, "vosk_${voskKey(l)}.zip")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(65536); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val p = (done * 95 / total).toInt()
                                if (p != last) { last = p; runOnUiThread { taskStart(k, p) } }
                            }
                        }
                    }
                }
                runOnUiThread { taskStart(k, 96) }
                val dir = voskDir(l); dir.deleteRecursively(); dir.mkdirs()
                unzip(zip, dir); zip.delete()
                File(dir, ".ok").writeText("ok")
                runOnUiThread { taskDone(k, "Речевой пакет «${l.name}» установлен ✓ Офлайн-распознавание готово.") }
            } catch (e: Throwable) {
                runOnUiThread { taskDone(k, "Не удалось скачать речевой пакет «${l.name}»: ${e.message}. Проверьте интернет и повторите.") }
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw Exception("bad zip")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zis.copyTo(it) } }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
    }

    private var vosk: org.vosk.android.SpeechService? = null
    private val voskCache = LinkedHashMap<String, org.vosk.Model>()

    private fun shutdownVosk() {
        val v = vosk ?: return
        vosk = null
        try { v.stop() } catch (e: Throwable) { }
        try { v.shutdown() } catch (e: Throwable) { }
    }

    private fun listenVosk(side: Int, a: Lang, b: Lang) {
        val path = voskPath(a)
        if (path == null) { botTv.text = "Речевой пакет повреждён. Скачайте заново: ⚙ → Языки для офлайна."; return }
        listeningSide = side
        (if (side == 0) micBot else micTop).text = "⏹"
        botTv.text = "Загружаю офлайн-модель…"
        thread {
            try {
                val key = voskKey(a)
                val model = synchronized(voskCache) {
                    voskCache[key] ?: run {
                        while (voskCache.size >= 2) { val k0 = voskCache.keys.first(); voskCache.remove(k0)?.close() }
                        org.vosk.Model(path).also { voskCache[key] = it }
                    }
                }
                runOnUiThread { startVosk(side, a, b, model) }
            } catch (e: Throwable) {
                runOnUiThread { idle(); botTv.text = "Не удалось загрузить офлайн-модель: ${e.message}" }
            }
        }
    }

    private fun startVosk(side: Int, a: Lang, b: Lang, model: org.vosk.Model) {
        if (listeningSide != side) return
        try {
            val svc = org.vosk.android.SpeechService(org.vosk.Recognizer(model, 16000f), 16000f)
            vosk = svc
            var done = false
            fun finish(t: String) {
                if (done) return
                done = true; shutdownVosk(); idle(); failCount = 0
                translate(side, t, a, b)
            }
            fun fail(msg: String) {
                if (done) return
                done = true; shutdownVosk(); idle(); botTv.text = msg
            }
            fun jf(h: String?, f: String) = try { JSONObject(h ?: "{}").optString(f) } catch (e: Exception) { "" }
            botTv.text = "Слушаю… (офлайн)"
            svc.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(h: String?) {
                    val t = jf(h, "partial")
                    if (t.isNotBlank() && !done) { if (side == 0) botTv.text = t else topTv.text = t }
                }
                override fun onResult(h: String?) { val t = jf(h, "text"); if (t.isNotBlank()) finish(t) }
                override fun onFinalResult(h: String?) {
                    val t = jf(h, "text")
                    if (t.isNotBlank()) finish(t) else fail("Речь не распознана. Нажмите микрофон и повторите.")
                }
                override fun onError(e: Exception?) { fail("Ошибка офлайн-распознавания: ${e?.message}") }
                override fun onTimeout() { fail("Не услышал речь. Нажмите микрофон и повторите.") }
            }, 15000)
        } catch (e: Throwable) { idle(); botTv.text = "Ошибка запуска офлайн-распознавания: ${e.message}" }
    }

    private fun ensureTts(l: Lang) { if (ttsReady) runSetup() }

    // ---------- мастер настройки: одно нажатие «Да» на каждый шаг ----------
    private val GOOGLE = "com.google.android.googlequicksearchbox"
    private val GTTS = "com.google.android.tts"
    private val wizardSeen = HashSet<String>()
    private var dialogOpen = false
    private var resumeWizard = false
    private var ttsEngine: String? = null
    private var ttsRetry = 0

    private fun installed(pkg: String) =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun openPlay(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
        catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    // true — показали окно (ждём ответа), false — шаг уже был показан
    private fun ask(key: String, title: String, msg: String, yes: String, action: () -> Unit): Boolean {
        if (dialogOpen) return true
        if (!wizardSeen.add(key)) return false
        dialogOpen = true
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setCancelable(false)
            .setPositiveButton(yes) { _, _ ->
                dialogOpen = false; resumeWizard = true
                try { action() } catch (e: Exception) { resumeWizard = false; runSetup() }
            }
            .setNegativeButton("Позже") { _, _ -> dialogOpen = false; runSetup() }
            .show()
        return true
    }

    private fun runSetup() {
        if (dialogOpen) return
        // 1. микрофон
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            && wizardSeen.add("mic")) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        // 2. приложение Google (распознавание речи)
        if (!installed(GOOGLE) && ask("g", "Нужно приложение Google",
                "Для распознавания речи нужно приложение «Google». Открыть Google Play? Останется нажать «Установить».",
                "Да, открыть") { openPlay(GOOGLE) }) return
        // 3. Службы речи Google (нейроголоса)
        if (!installed(GTTS) && ask("t", "Нейроголоса Google",
                "Для качественной озвучки нужны «Службы речи от Google». Открыть Google Play? Останется нажать «Установить».",
                "Да, открыть") { openPlay(GTTS) }) return
        // 4. голоса выбранных языков
        if (ttsReady) {
            val miss = listOf(src(), dst()).filter {
                tts?.isLanguageAvailable(Locale.forLanguageTag(it.tag)) == TextToSpeech.LANG_MISSING_DATA
            }
            if (miss.isNotEmpty() && ask("v:" + miss.joinToString { it.tag }, "Голоса для озвучки",
                    "Нужно скачать голоса: ${miss.joinToString { it.name }}. Открыть список голосов? Нажмите на язык и значок загрузки.",
                    "Да, открыть") { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }) return
        }
        // 5. речевой пакет на Android 12 и старше (на 13+ он скачивается сам)
        if (Build.VERSION.SDK_INT < 33 && ask("sp", "Офлайн-распознавание речи",
                "Откройте настройки голосового ввода и скачайте офлайн-пакеты нужных языков. Открыть настройки?",
                "Да, открыть") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }) return
    }

    override fun onResume() {
        super.onResume()
        val rw = resumeWizard; resumeWizard = false
        pendingApk?.let { f ->
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) { pendingApk = null; installApk(f) }
        }
        if (ttsEngine != GTTS && ttsRetry < 3 && installed(GTTS)) {
            ttsRetry++; ttsReady = false; tts?.shutdown(); initTts(GTTS); return
        }
        if (rw) runSetup()
    }

    // ---------- обновления приложения и языковых пакетов ----------
    private val REPO = "pugachej4-sudo/talking-translator-android"
    private val ACTION_INSTALL = "com.talk.translator.INSTALL_RESULT"
    private var pendingApk: File? = null

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val conf = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    conf?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> botTv.text = "Обновление установлено ✓"
                else -> botTv.text = "Не удалось установить обновление: " +
                    i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) +
                    ". Если это ошибка подписи — удалите старую версию и поставьте новую вручную."
            }
        }
    }

    private fun autoMaintenance() {
        val sp = getSharedPreferences("tt", MODE_PRIVATE)
        if (!sp.getBoolean("auto", true)) return
        checkUpdate(true)
        if (System.currentTimeMillis() - sp.getLong("langUpd", 0) > 7L * 24 * 3600 * 1000) updateLangs(true)
    }

    private fun checkUpdate(silent: Boolean) {
        if (!silent) botTv.text = "Проверяю обновления…"
        thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "TalkingTranslator")
                c.connectTimeout = 10000; c.readTimeout = 10000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val remote = j.getString("tag_name").substringAfterLast('-').toIntOrNull() ?: 0
                var url = ""
                val assets = j.getJSONArray("assets")
                for (k in 0 until assets.length()) {
                    val a = assets.getJSONObject(k)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                runOnUiThread {
                    if (remote > BuildConfig.VERSION_CODE && url.isNotEmpty()) askUpdate(remote, url)
                    else if (!silent) botTv.text = "У вас последняя версия (${BuildConfig.VERSION_CODE}) ✓"
                }
            } catch (e: Exception) {
                if (!silent) runOnUiThread { botTv.text = "Не удалось проверить обновления. Нужен интернет." }
            }
        }
    }

    private fun askUpdate(remote: Int, url: String) {
        AlertDialog.Builder(this).setTitle("Доступно обновление")
            .setMessage("Новая версия $remote (у вас ${BuildConfig.VERSION_CODE}). Скачать и установить?")
            .setPositiveButton("Да, обновить") { _, _ -> downloadApk(url) }
            .setNegativeButton("Позже", null).show()
    }

    private fun downloadApk(url: String) {
        taskStart("Обновление приложения", 0, 600000)
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val total = c.contentLengthLong
                val f = File(cacheDir, "update.apk")
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; runOnUiThread { taskStart("Обновление приложения", p, 600000) } }
                            }
                        }
                    }
                }
                runOnUiThread { taskDone("Обновление приложения", "Устанавливаю обновление…"); installApk(f) }
            } catch (e: Exception) {
                runOnUiThread { taskDone("Обновление приложения", "Ошибка загрузки обновления: ${e.message}") }
            }
        }
    }

    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingApk = f
            botTv.text = "Разрешите приложению устанавливать обновления и вернитесь назад."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val pi = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { sess ->
                f.inputStream().use { inp ->
                    sess.openWrite("app.apk", 0, f.length()).use { out -> inp.copyTo(out); sess.fsync(out) }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pend = PendingIntent.getBroadcast(this, id, Intent(ACTION_INSTALL).setPackage(packageName), flags)
                sess.commit(pend.intentSender)
            }
            botTv.text = "Устанавливаю обновление…"
        } catch (e: Exception) { botTv.text = "Ошибка установки: ${e.message}" }
    }

    private fun updateLangs(silent: Boolean) {
        val mm = RemoteModelManager.getInstance()
        if (!silent) taskStart("Языковые пакеты")
        mm.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { set ->
            val all = HashSet<TranslateRemoteModel>(set)
            listOf(src(), dst()).filter { it.ml != TranslateLanguage.ENGLISH }
                .forEach { all.add(TranslateRemoteModel.Builder(it.ml).build()) }
            if (all.isEmpty()) { if (!silent) taskDone("Языковые пакеты", "Языковые пакеты актуальны ✓"); return@addOnSuccessListener }
            var left = all.size
            all.forEach { m ->
                mm.download(m, DownloadConditions.Builder().build()).addOnCompleteListener {
                    if (--left == 0 && !silent) taskDone("Языковые пакеты", "Языковые пакеты обновлены ✓ (${all.size})")
                }
            }
        }
        prepared.clear(); ensureSpeech(src(), !silent); ensureSpeech(dst(), !silent)
        getSharedPreferences("tt", MODE_PRIVATE).edit().putLong("langUpd", System.currentTimeMillis()).apply()
    }

    private fun showSettings() {
        val sp = getSharedPreferences("tt", MODE_PRIVATE)
        val auto = sp.getBoolean("auto", true)
        val llmState = if (!llmReady()) "не установлена" else if (sp.getBoolean("llm", false)) "ВКЛ" else "ВЫКЛ"
        val items = arrayOf(
            "Обновить приложение (версия ${BuildConfig.VERSION_CODE})",
            "Языки для офлайна: что скачано / скачать ещё",
            "ИИ-перевод офлайн (лучшее качество): $llmState",
            "Обновить языковые пакеты",
            "Автообновление при запуске: " + if (auto) "ВКЛ" else "ВЫКЛ",
            "Режим перевода: " + modeName(),
            "Распознавание речи: " + if (sp.getBoolean("sys", false)) "СИСТЕМНОЕ ОКНО (переключить на встроенное)" else "ВСТРОЕННОЕ (переключить на системное окно)",
            "Мастер настройки (Google, голоса)")
        AlertDialog.Builder(this).setTitle("Настройки").setItems(items) { _, i ->
            when (i) {
                0 -> checkUpdate(false)
                1 -> showLangManager()
                2 -> showLlmDialog()
                3 -> updateLangs(false)
                4 -> { sp.edit().putBoolean("auto", !auto).apply()
                       Toast.makeText(this, if (!auto) "Автообновление включено" else "Автообновление выключено", Toast.LENGTH_SHORT).show() }
                5 -> showModeDialog()
                6 -> { val v = !sp.getBoolean("sys", false); sp.edit().putBoolean("sys", v).apply(); failCount = 0
                       Toast.makeText(this, if (v) "Системное окно голосового ввода" else "Встроенное распознавание", Toast.LENGTH_SHORT).show() }
                7 -> { wizardSeen.clear(); prepared.clear(); ensureLang(src()); ensureLang(dst()); runSetup() }
            }
        }.show()
    }

    // ---------- системное окно голосового ввода (запасной вариант) ----------
    private var failCount = 0
    private var dlgSide = 0
    private var dlgA: Lang? = null
    private var dlgB: Lang? = null

    private fun errText(c: Int) = when (c) {
        7 -> "Не расслышал. Нажмите микрофон и говорите сразу, ближе к телефону."
        6 -> "Не услышал речь. Нажмите микрофон и начните говорить."
        1, 2 -> if (!online) "Офлайн: для этого языка не установлен речевой пакет. Откройте ⋮ → ⚙ → Языки для офлайна и скачайте его." else "Проблема с сетью. Проверьте интернет."
        3 -> "Ошибка записи звука."
        8 -> "Распознаватель занят, повторите."
        9 -> "Нет разрешения на микрофон."
        else -> "Ошибка распознавания ($c)."
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4) { if (resultCode == RESULT_OK) data?.data?.let { importLlm(it) }; return }
        if (requestCode != 3) return
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val a = dlgA; val b = dlgB
        if (resultCode == RESULT_OK && !t.isNullOrBlank() && a != null && b != null) translate(dlgSide, t, a, b)
        else botTv.text = "Речь не распознана. Нажмите микрофон и повторите."
    }

    private fun hasNet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun modeName() = when (mode) {
        0 -> "ОНЛАЙН"
        1 -> "ОФЛАЙН"
        else -> "АВТО (сейчас " + (if (online) "онлайн" else "офлайн") + ")"
    }

    private fun showModeDialog() {
        val items = arrayOf("Онлайн", "Офлайн", "Авто: онлайн, а если нет сети — офлайн")
        AlertDialog.Builder(this).setTitle("Режим перевода")
            .setSingleChoiceItems(items, mode) { d, i -> setMode(i); d.dismiss() }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun setMode(m: Int) {
        mode = m; autoForceOffline = false
        getSharedPreferences("tt", MODE_PRIVATE).edit().putInt("mode", m).apply()
        Toast.makeText(this, "Режим: " + modeName(), Toast.LENGTH_SHORT).show()
        if (m != 0) offlineCheck()   // офлайн (и запасной офлайн в авто-режиме) должен быть готов
    }

    // ---------- индикатор загрузок: «Подождите, пожалуйста» + полоса + «N из M» ----------
    private val tasks = LinkedHashMap<String, Int>()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var totalTasks = 0
    private var doneTasks = 0
    private var startMs = 0L
    private var ticking = false
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (tasks.isNotEmpty()) { showTasks(); ui.postDelayed(this, 1000) } else ticking = false
        }
    }

    private fun taskStart(key: String, pct: Int = -1, timeoutMs: Long = 180000) {
        if (!tasks.containsKey(key)) {
            if (tasks.isEmpty()) { totalTasks = 0; doneTasks = 0; startMs = System.currentTimeMillis() }
            totalTasks++
            ui.postDelayed({
                if (tasks.containsKey(key))
                    taskDone(key, "$key: загрузка идёт в фоне. Онлайн-режим уже работает, ждать не нужно.")
            }, timeoutMs)
        }
        tasks[key] = pct
        showTasks()
        if (!ticking) { ticking = true; ui.postDelayed(tick, 1000) }
    }

    private fun taskDone(key: String, msg: String) {
        if (tasks.remove(key) != null) doneTasks++
        if (tasks.isEmpty()) { pb.visibility = View.GONE; botTv.text = msg } else showTasks()
    }

    private fun showTasks() {
        val sec = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        val lines = tasks.entries.joinToString("\n") { it.key + if (it.value >= 0) " — ${it.value}%" else "…" }
        botTv.text = lines + "\n\nЗагружено $doneTasks из $totalTasks · прошло %d:%02d\nПодождите, пожалуйста".format(sec / 60, sec % 60)
        pb.visibility = View.VISIBLE
        val partial = tasks.values.filter { it >= 0 }.sum() / 100.0
        if (totalTasks <= 1 && partial == 0.0) pb.isIndeterminate = true
        else {
            pb.isIndeterminate = false
            pb.progress = (((doneTasks + partial) / totalTasks) * 100).toInt().coerceIn(0, 100)
        }
    }

    // ---------- менеджер языков для офлайна ----------
    private class Status(val tr: Boolean, val sp: Boolean?, val tts: Boolean)

    private fun List<String>.hasLang(l: Lang) =
        any { it.equals(l.tag, true) || it.substringBefore('-').equals(l.tag.substringBefore('-'), true) }

    private fun ttsOffline(l: Lang): Boolean {
        val t = tts ?: return false
        if (!ttsReady) return false
        val loc = Locale.forLanguageTag(l.tag)
        if (t.isLanguageAvailable(loc) < TextToSpeech.LANG_AVAILABLE) return false
        return t.voices?.any { it.locale.language == loc.language && !it.isNetworkConnectionRequired } ?: true
    }

    private fun downloadModel(l: Lang) {
        val k = "Перевод: ${l.name}"
        taskStart(k)
        RemoteModelManager.getInstance()
            .download(TranslateRemoteModel.Builder(l.ml).build(), DownloadConditions.Builder().build())
            .addOnSuccessListener { taskDone(k, "Перевод «${l.name}» скачан ✓") }
            .addOnFailureListener { taskDone(k, "Не удалось скачать перевод «${l.name}». Нужен интернет.") }
    }

    private fun pollSpeech(r: SpeechRecognizer, i: Intent, l: Lang, k: String, left: Int) {
        if (Build.VERSION.SDK_INT < 33 || left <= 0) return
        ui.postDelayed({
            if (!tasks.containsKey(k)) return@postDelayed
            try {
                r.checkRecognitionSupport(i, mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        if (s.installedOnDeviceLanguages.hasLang(l)) taskDone(k, "Речевой пакет «${l.name}» скачан ✓")
                        else pollSpeech(r, i, l, k, left - 1)
                    }
                    override fun onError(error: Int) { pollSpeech(r, i, l, k, left - 1) }
                })
            } catch (e: Exception) { }
        }, 4000)
    }

    // что реально скачано: перевод (ML Kit), речь (Google), голос (TTS)
    private fun collectStatus(cb: (Map<String, Status>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnCompleteListener { task ->
                val codes: Set<String> = if (task.isSuccessful) task.result.map { it.language }.toSet() else emptySet()
                var called = false
                fun finish(installed: List<String>?) {
                    if (called) return
                    called = true
                    val m = LinkedHashMap<String, Status>()
                    langs.forEach { l ->
                        val g = installed?.hasLang(l)
                        val sp = if (voskReady(l) || g == true) true else if (installed != null || voskUrl(l) != null) false else null
                        m[l.tag] = Status(l.ml == TranslateLanguage.ENGLISH || l.ml in codes, sp, ttsOffline(l))
                    }
                    cb(m)
                }
                ui.postDelayed({ finish(null) }, 3000)
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        val r = newRecognizer(); helpers.add(r)
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, src().tag)
                        r.checkRecognitionSupport(i, mainExecutor, object : RecognitionSupportCallback {
                            override fun onSupportResult(s: RecognitionSupport) { finish(s.installedOnDeviceLanguages) }
                            override fun onError(error: Int) { finish(null) }
                        })
                    } catch (e: Exception) { finish(null) }
                } else finish(null)
            }
    }

    private fun showLangManager() {
        botTv.text = "Проверяю языки…"
        collectStatus { st ->
            botTv.text = if (botText.isNotEmpty()) botText else "Нажмите микрофон"
            fun mark(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "?" }
            val labels = langs.map { l ->
                val s = st[l.tag]!!
                "${l.name}\n   перевод ${mark(s.tr)} · речь ${mark(s.sp)} · голос ${mark(s.tts)}"
            }
            val d = android.app.Dialog(this)
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val lv = ListView(this).apply {
                choiceMode = ListView.CHOICE_MODE_MULTIPLE
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_multiple_choice, labels)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val head = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(8), dp(8), dp(4))
                addView(TextView(this@MainActivity).apply {
                    text = "Языки для офлайна"; textSize = 18f; setTextColor(Color.BLACK)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "✕"; textSize = 26f; setTextColor(Color.DKGRAY)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    setOnClickListener { d.dismiss() }
                })
            }
            val legend = TextView(this).apply {
                text = "✓ есть   ✗ нет   ? неизвестно. Отметьте галочками, что скачать."
                textSize = 13f; setTextColor(Color.GRAY); setPadding(dp(20), 0, dp(20), dp(6))
            }
            val btn = Button(this).apply {
                text = "Скачать выбранные"; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(TEAL); cornerRadius = dp(26).toFloat() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) }
                setOnClickListener {
                    val pos = lv.checkedItemPositions
                    val sel = langs.filterIndexed { i, _ -> pos.get(i) }
                    if (sel.isEmpty()) Toast.makeText(this@MainActivity, "Отметьте языки галочками", Toast.LENGTH_SHORT).show()
                    else { d.dismiss(); downloadSelected(sel, st) }
                }
            }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
                addView(head); addView(legend); addView(lv); addView(btn)
            }
            d.setContentView(root)
            d.show()
            d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.85).toInt())
            d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun downloadSelected(sel: List<Lang>, st: Map<String, Status>) {
        sel.forEach { l ->
            if (l.ml != TranslateLanguage.ENGLISH) downloadModel(l)
            ensureSpeech(l, true)
        }
        val noVoice = sel.filter { st[it.tag]?.tts == false }
        if (noVoice.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Голоса для озвучки")
                .setMessage("Для офлайн-озвучки нужно скачать голоса: ${noVoice.joinToString { it.name }}. Открыть список голосов? Нажмите на язык и значок загрузки.")
                .setPositiveButton("Да, открыть") { _, _ ->
                    try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e: Exception) { }
                }
                .setNegativeButton("Позже", null).show()
        }
    }

    private fun offlineCheck() {
        collectStatus { st ->
            val miss = listOf(src(), dst()).distinct().filter { l ->
                val s = st[l.tag]!!; !s.tr || s.sp == false || !s.tts
            }
            if (miss.isEmpty()) {
                Toast.makeText(this, "Офлайн готов ✓", Toast.LENGTH_SHORT).show()
                return@collectStatus
            }
            val txt = miss.joinToString("\n") { l ->
                val s = st[l.tag]!!
                "• ${l.name}: " + listOfNotNull(
                    if (!s.tr) "перевод" else null,
                    if (s.sp == false) "распознавание речи" else null,
                    if (!s.tts) "голос" else null).joinToString(", ")
            }
            AlertDialog.Builder(this).setTitle("Офлайн пока не готов")
                .setMessage("Не хватает:\n$txt\n\nСкачать сейчас? Нужен интернет.")
                .setPositiveButton("Да, скачать") { _, _ -> downloadSelected(miss, st) }
                .setNegativeButton("Позже", null).show()
        }
    }

    // ---------- перевод: онлайн (Google) → офлайн (ИИ-модель или ML Kit) ----------
    private fun translate(side: Int, text: String, a: Lang, b: Lang) {
        botTv.text = "Перевожу…"
        if (online) {
            thread {
                val r = try { googleTranslate(text, a, b) } catch (e: Exception) { null }
                runOnUiThread {
                    if (!r.isNullOrBlank()) { show(side, text, r, a, b); speak(r, b) }
                    else translateOffline(side, text, a, b)
                }
            }
        } else translateOffline(side, text, a, b)
    }

    private fun translateOffline(side: Int, text: String, a: Lang, b: Lang) {
        if (getSharedPreferences("tt", MODE_PRIVATE).getBoolean("llm", false) && llmReady()) translateLlm(side, text, a, b)
        else translateMl(side, text, a, b)
    }

    private fun gcode(l: Lang) = if (l.ml == TranslateLanguage.CHINESE) "zh-CN" else l.tag.substringBefore('-')

    private fun googleTranslate(text: String, a: Lang, b: Lang): String? {
        val u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${gcode(a)}&tl=${gcode(b)}&dt=t&q=" +
            java.net.URLEncoder.encode(text, "UTF-8")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode != 200) return null
        val arr = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.getJSONArray(i).optString(0))
        return sb.toString().trim()
    }

    // ---------- офлайн ИИ-перевод (языковая модель на телефоне) ----------
    private val DEFAULT_LLM_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private val enNames = mapOf("ru" to "Russian", "en" to "English", "de" to "German", "fr" to "French",
        "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese", "pl" to "Polish", "uk" to "Ukrainian",
        "tr" to "Turkish", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic")
    private fun enName(l: Lang) = enNames[l.tag.substringBefore('-')] ?: l.name
    private val llmFile: File get() = File(filesDir, "llm/model.task")
    private fun llmReady() = llmFile.exists() && llmFile.length() > 50_000_000L
    private var llm: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null

    private fun llmPrompt(text: String, a: Lang, b: Lang): String {
        val ins = "Translate the following text from ${enName(a)} to ${enName(b)}. Output only the translation, without any comments.\n\n$text"
        val name = getSharedPreferences("tt", MODE_PRIVATE).getString("llmName", "") ?: ""
        return if (name.contains("gemma", true)) "<start_of_turn>user\n$ins<end_of_turn>\n<start_of_turn>model\n"
        else "<|im_start|>system\nYou are a professional translator.<|im_end|>\n<|im_start|>user\n$ins<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun translateLlm(side: Int, text: String, a: Lang, b: Lang) {
        botTv.text = "ИИ-перевод… (первый запуск модели может занять около минуты)"
        thread {
            try {
                val m = synchronized(this) {
                    llm ?: com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(
                        this,
                        com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(llmFile.absolutePath).setMaxTokens(768).build()
                    ).also { llm = it }
                }
                var out = m.generateResponse(llmPrompt(text, a, b)).trim()
                for (stop in listOf("<end_of_turn>", "<|im_end|>", "<|endoftext|>")) out = out.substringBefore(stop)
                out = out.trim().trim('"', '«', '»').trim()
                if (out.isBlank()) throw Exception("пустой ответ")
                runOnUiThread { show(side, text, out, a, b); speak(out, b) }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "ИИ-модель не сработала (${e.message}). Использую обычный перевод.", Toast.LENGTH_LONG).show()
                    translateMl(side, text, a, b)
                }
            }
        }
    }

    private fun finishLlm(name: String) {
        getSharedPreferences("tt", MODE_PRIVATE).edit().putString("llmName", name).putBoolean("llm", true).apply()
        try { llm?.close() } catch (e: Throwable) { }
        llm = null
    }

    private fun downloadLlm(url: String) {
        val k = "ИИ-модель перевода"
        if (tasks.containsKey(k)) return
        taskStart(k, 0, 7200000)
        thread {
            try {
                val dir = File(filesDir, "llm"); dir.mkdirs()
                val tmp = File(dir, "model.part")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 60000
                c.setRequestProperty("User-Agent", "TalkingTranslator")
                if (c.responseCode == 401 || c.responseCode == 403)
                    throw Exception("модель требует входа на сайте. Скачайте файл в браузере и выберите его кнопкой «Выбрать файл»")
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pc = (done * 100 / total).toInt()
                                if (pc != last) { last = pc; runOnUiThread { taskStart(k, pc) } }
                            }
                        }
                    }
                }
                llmFile.delete(); tmp.renameTo(llmFile)
                runOnUiThread { finishLlm(url.substringAfterLast('/').substringBefore('?')); taskDone(k, "ИИ-модель установлена ✓ Включена для офлайн-перевода.") }
            } catch (e: Throwable) {
                runOnUiThread { taskDone(k, "Не удалось скачать ИИ-модель: ${e.message}") }
            }
        }
    }

    private fun importLlm(uri: Uri) {
        val k = "ИИ-модель перевода"
        if (tasks.containsKey(k)) return
        taskStart(k, -1, 7200000)
        thread {
            try {
                var name = "model.task"; var size = -1L
                contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val ni = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0) name = cur.getString(ni)
                        val si = cur.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (si >= 0) size = cur.getLong(si)
                    }
                }
                val dir = File(filesDir, "llm"); dir.mkdirs()
                val tmp = File(dir, "model.part")
                val inp0 = contentResolver.openInputStream(uri) ?: throw Exception("не удалось открыть файл")
                inp0.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (size > 0) {
                                val pc = (done * 100 / size).toInt()
                                if (pc != last) { last = pc; runOnUiThread { taskStart(k, pc) } }
                            }
                        }
                    }
                }
                if (tmp.length() < 50_000_000L) throw Exception("файл слишком маленький, это не модель")
                llmFile.delete(); tmp.renameTo(llmFile)
                runOnUiThread { finishLlm(name); taskDone(k, "ИИ-модель установлена ✓ Включена для офлайн-перевода.") }
            } catch (e: Throwable) {
                runOnUiThread { taskDone(k, "Не удалось установить ИИ-модель: ${e.message}") }
            }
        }
    }

    private fun showLlmDialog() {
        val sp = getSharedPreferences("tt", MODE_PRIVATE)
        val status = TextView(this).apply { textSize = 15f; setTextColor(Color.BLACK) }
        val url = EditText(this).apply { setText(sp.getString("llmUrl", DEFAULT_LLM_URL)); textSize = 12f }
        val sw = Switch(this).apply { text = "Использовать для офлайн-перевода" }
        fun refresh() {
            status.text = if (llmReady()) "Модель установлена: ${sp.getString("llmName", "")} (${llmFile.length() / 1048576} МБ)"
            else "Модель не установлена."
            sw.isChecked = sp.getBoolean("llm", false) && llmReady()
        }
        sw.setOnCheckedChangeListener { _, v ->
            if (v && !llmReady()) { sw.isChecked = false; Toast.makeText(this, "Сначала установите модель", Toast.LENGTH_SHORT).show() }
            else sp.edit().putBoolean("llm", v).apply()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val d = AlertDialog.Builder(this).setTitle("ИИ-перевод офлайн (лучшее качество)")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Готово", null).create()
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
        col.addView(status); col.addView(sw)
        col.addView(TextView(this).apply { text = "\nСсылка на файл модели (.task, 0,5–3 ГБ):"; textSize = 13f })
        col.addView(url)
        col.addView(btn("Скачать по ссылке") {
            val u = url.text.toString().trim()
            sp.edit().putString("llmUrl", u).apply(); d.dismiss(); downloadLlm(u)
        })
        col.addView(btn("Выбрать файл на телефоне") {
            d.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 4)
        })
        col.addView(btn("Удалить модель") {
            try { llm?.close() } catch (e: Throwable) { }
            llm = null; llmFile.delete(); sp.edit().putBoolean("llm", false).apply(); refresh()
        })
        refresh()
        d.show()
    }

    // ---------- перевод ----------
    private fun translateMl(side: Int, text: String, a: Lang, b: Lang) {
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
            menu.add(0, 6, 0, "⚙ Настройки и обновления")
            menu.add(0, 1, 0, "Режим перевода: " + modeName())
            menu.add(0, 2, 1, "История")
            menu.add(0, 3, 2, "Избранное")
            menu.add(0, 4, 3, "Языки для офлайна (скачать / проверить)")
            menu.add(0, 5, 4, "Мастер настройки")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showModeDialog()
                    2 -> showHist(false)
                    3 -> showHist(true)
                    4 -> showLangManager()
                    6 -> showSettings()
                    5 -> { wizardSeen.clear(); prepared.clear(); ensureLang(src()); ensureLang(dst()); runSetup() }
                }
                true
            }
            show()
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

    override fun onDestroy() { try { unregisterReceiver(installReceiver) } catch (e: Exception) { }; try { llm?.close() } catch (e: Throwable) { }; shutdownVosk(); voskCache.values.forEach { try { it.close() } catch (e: Throwable) { } }; helpers.forEach { it.destroy() }; rec?.destroy(); tts?.shutdown(); super.onDestroy() }
}
