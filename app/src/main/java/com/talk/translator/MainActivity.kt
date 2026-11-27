package com.talk.translator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Нейросетевая озвучка (реализация PiperImpl подключается при сборке, если доступна). */
interface NeuralTts {
    fun load(modelDir: String): Boolean
    fun speak(text: String)
    fun stop()
    fun release()
}

class MainActivity : Activity() {
    class Lang(val name: String, val tag: String)
    class Voice(val id: String, val title: String, val model: String)

    private val langs = listOf(
        Lang("русский", "ru-RU"), Lang("английский", "en-US"), Lang("немецкий", "de-DE"),
        Lang("французский", "fr-FR"), Lang("испанский", "es-ES"), Lang("итальянский", "it-IT"),
        Lang("португальский", "pt-BR"), Lang("польский", "pl-PL"), Lang("украинский", "uk-UA"),
        Lang("турецкий", "tr-TR"), Lang("китайский", "zh-CN"), Lang("японский", "ja-JP"),
        Lang("корейский", "ko-KR"), Lang("арабский", "ar-SA")
    )

    // нейросетевые голоса Piper (офлайн)
    private val voices: Map<String, List<Voice>> = mapOf(
        "ru" to listOf(
            Voice("irina", "Ирина (жен.)", "vits-piper-ru_RU-irina-medium"),
            Voice("denis", "Денис (муж.)", "vits-piper-ru_RU-denis-medium"),
            Voice("dmitri", "Дмитрий (муж.)", "vits-piper-ru_RU-dmitri-medium"),
            Voice("ruslan", "Руслан (муж.)", "vits-piper-ru_RU-ruslan-medium")),
        "en" to listOf(
            Voice("lessac", "Lessac (жен.)", "vits-piper-en_US-lessac-medium"),
            Voice("ryan", "Ryan (муж.)", "vits-piper-en_US-ryan-medium")),
        "es" to listOf(Voice("davefx", "DaveFX (муж.)", "vits-piper-es_ES-davefx-medium")),
        "de" to listOf(Voice("thorsten", "Thorsten (муж.)", "vits-piper-de_DE-thorsten-medium")),
        "fr" to listOf(Voice("siwis", "Siwis (жен.)", "vits-piper-fr_FR-siwis-medium"))
    )
    private val sampleText = mapOf(
        "ru" to "Здравствуйте! Это проверка голоса.", "en" to "Hello! This is a voice test.",
        "es" to "¡Hola! Esto es una prueba de voz.", "de" to "Hallo! Das ist ein Stimmtest.",
        "fr" to "Bonjour ! Ceci est un test de voix.")

    private val TEAL = Color.parseColor("#009688")
    private val DARK = Color.parseColor("#00796B")
    private val LIGHT = Color.parseColor("#B2E5DD")
    private val GOOGLE = "com.google.android.googlequicksearchbox"
    private val GTTS = "com.google.android.tts"
    private val REPO = "pugachej4-sudo/talking-translator-android"
    private val ACTION_INSTALL = "com.talk.translator.INSTALL_RESULT"

    private val prefs: SharedPreferences get() = getSharedPreferences("tt", MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())

    private var topText = ""
    private var botText = ""
    private var listeningSide = -1
    private var pendingSide = -1
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsEngine: String? = null
    private var ttsRetry = 0

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

    // ---------------------------------------------------------------- интерфейс
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

    private fun makeSpinner(sel: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.name })
        setSelection(sel)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { runSetup() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inf = IntentFilter(ACTION_INSTALL)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, inf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, inf)
        initTts("com.google.android.tts")
        // освободить место: удалить старые офлайн-модели (Vosk, ИИ-перевод)
        thread { File(filesDir, "vosk").deleteRecursively(); File(filesDir, "llm").deleteRecursively() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Color.WHITE) }

        // верхняя половина: перевёрнута на 180° для собеседника
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

        // нижняя половина
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
        pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(32), 0, dp(32), dp(8)) }
        }
        micBot = circle("🎙", TEAL, 76) { listen(0) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(circle("🕘", LIGHT, 52) { showHist(false) })
            addView(micBot)
            addView(circle("✕", Color.LTGRAY, 52) { clearAll() })
        }
        spSrc = makeSpinner(0)
        spDst = makeSpinner(1)
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
            addView(tools); addView(botTv); addView(pb); addView(bar); addView(langRow)
        }
        root.addView(top); root.addView(bottom)
        setContentView(root)
        root.post { runSetup(); autoMaintenance() }
    }

    // ---------------------------------------------------------------- распознавание речи
    private var retryLeft = 1
    private var failCount = 0
    private var useDefaultRec = false
    private var dlgSide = 0
    private var dlgA: Lang? = null
    private var dlgB: Lang? = null

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

    private fun idle() { listeningSide = -1; micBot.text = "🎙"; micTop.text = "🎙" }

    private fun errText(c: Int) = when (c) {
        7 -> "Не расслышал. Нажмите микрофон и говорите сразу, ближе к телефону."
        6 -> "Не услышал речь. Нажмите микрофон и начните говорить."
        1, 2 -> "Нет интернета. Для распознавания речи нужен интернет."
        3 -> "Ошибка записи звука."
        8 -> "Распознаватель занят, повторите."
        9 -> "Нет разрешения на микрофон."
        12, 13 -> "Служба распознавания не поддерживает этот язык (код $c). Включите системное окно: ⋮ → ⚙ → Распознавание речи."
        else -> "Ошибка распознавания ($c)."
    }

    private fun listen(side: Int, retry: Boolean = false) {
        if (listeningSide == side) { rec?.stopListening(); return }
        if (listeningSide != -1) return
        if (!retry) retryLeft = 1
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSide = side
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        val a = if (side == 0) src() else dst()
        val b = if (side == 0) dst() else src()
        if (prefs.getBoolean("sys", false)) {
            dlgSide = side; dlgA = a; dlgB = b
            val di = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.tag)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите: ${a.name}")
            try { startActivityForResult(di, 3) } catch (e: Exception) { botTv.text = "Нет системного голосового ввода." }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            botTv.text = "Нет службы распознавания речи. Установите приложение «Google»."; return
        }
        rec?.destroy()
        rec = newRecognizer().apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    if (side == 0) botTv.text = t else topTv.text = t
                }
                override fun onError(code: Int) {
                    idle()
                    if (code == 1 || code == 2 || code == 9) { botTv.text = errText(code); return }
                    if ((code == 7 || code == 6) && retryLeft > 0) {
                        retryLeft--
                        botTv.text = "Слушаю…"
                        ui.postDelayed({ if (listeningSide == -1) listen(side, true) }, 300)
                        return
                    }
                    if (code != 7 && code != 6 && !useDefaultRec) {
                        useDefaultRec = true
                        botTv.text = "Пробую другую службу распознавания…"
                        ui.postDelayed({ if (listeningSide == -1) listen(side, true) }, 300)
                        return
                    }
                    failCount++
                    if (failCount >= 2 && !prefs.getBoolean("sys", false)) {
                        prefs.edit().putBoolean("sys", true).apply()
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
        listeningSide = side
        (if (side == 0) micBot else micTop).text = "⏹"
        botTv.text = "Слушаю…"
        rec?.startListening(i)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, g: IntArray) {
        if (c == 2) { runSetup(); return }
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED && pendingSide != -1) listen(pendingSide)
        pendingSide = -1
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 3) return
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val a = dlgA; val b = dlgB
        if (resultCode == RESULT_OK && !t.isNullOrBlank() && a != null && b != null) translate(dlgSide, t, a, b)
        else botTv.text = "Речь не распознана. Нажмите микрофон и повторите."
    }

    // ---------------------------------------------------------------- перевод (онлайн: Google → Microsoft Edge)
    private fun translate(side: Int, text: String, a: Lang, b: Lang) {
        botTv.text = "Перевожу…"
        thread {
            var r: String? = try { googleTranslate(text, a, b) } catch (e: Exception) { null }
            if (r.isNullOrBlank()) r = try { edgeTranslate(text, a, b) } catch (e: Exception) { null }
            val res = r
            runOnUiThread {
                if (!res.isNullOrBlank()) { show(side, text, res, a, b); speak(res, b) }
                else botTv.text = "Не удалось перевести. Проверьте интернет и повторите."
            }
        }
    }

    private fun gcode(l: Lang) = l.tag.substringBefore('-').let { if (it == "zh") "zh-CN" else it }

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

    private var edgeToken: String? = null
    private fun ecode(l: Lang) = l.tag.substringBefore('-').let { if (it == "zh") "zh-Hans" else it }

    private fun edgeTranslate(text: String, a: Lang, b: Lang): String? {
        if (edgeToken == null) {
            val t = URL("https://edge.microsoft.com/translate/auth").openConnection() as HttpURLConnection
            t.connectTimeout = 8000; t.readTimeout = 8000
            t.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (t.responseCode != 200) return null
            edgeToken = t.inputStream.bufferedReader().readText().trim()
        }
        val u = "https://api-edge.cognitive.microsofttranslator.com/translate?from=${ecode(a)}&to=${ecode(b)}&api-version=3.0"
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $edgeToken")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONArray().put(JSONObject().put("Text", text)).toString().toByteArray()) }
        if (c.responseCode == 401) { edgeToken = null; return null }
        if (c.responseCode != 200) return null
        return JSONArray(c.inputStream.bufferedReader().readText())
            .getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
    }

    private fun show(side: Int, text: String, res: String, a: Lang, b: Lang) {
        if (side == 0) { botText = text; topText = res } else { topText = text; botText = res }
        botTv.text = botText; topTv.text = topText
        val h = loadHist()
        h.add(0, JSONObject().put("src", a.name).put("dst", b.name).put("text", text).put("tr", res)
            .put("fav", false).put("time", SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())))
        saveHist(h)
    }

    // ---------------------------------------------------------------- озвучка
    private val neural: NeuralTts? by lazy {
        try { Class.forName("com.talk.translator.PiperImpl").getDeclaredConstructor().newInstance() as NeuralTts }
        catch (e: Throwable) { null }
    }
    private val neuralLock = Any()
    private var loadedModel = ""

    private fun voiceDir(v: Voice) = File(filesDir, "piper/" + v.model)
    private fun voiceReady(v: Voice) = File(voiceDir(v), ".ok").exists()
    private fun selectedVoice(l: Lang): Voice? {
        val key = l.tag.substringBefore('-')
        val list = voices[key] ?: return null
        val id = prefs.getString("voice_$key", list[0].id)
        return list.firstOrNull { it.id == id } ?: list[0]
    }

    private fun initTts(engine: String?) {
        ttsEngine = engine
        val cb = TextToSpeech.OnInitListener { st ->
            if (st == TextToSpeech.SUCCESS) { ttsReady = true; runSetup() }
            else if (engine != null) initTts(null)
        }
        tts = if (engine != null) TextToSpeech(this, cb, engine) else TextToSpeech(this, cb)
    }

    private fun systemSpeak(text: String, l: Lang) {
        val t = tts ?: return
        t.language = Locale.forLanguageTag(l.tag)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun speak(text: String, l: Lang) {
        if (text.isBlank()) return
        val n = neural
        val v = selectedVoice(l)
        if (prefs.getString("tts", "neural") == "neural" && n != null && v != null && voiceReady(v)) {
            thread {
                try {
                    synchronized(neuralLock) {
                        n.stop()
                        if (loadedModel != v.model) {
                            if (!n.load(voiceDir(v).absolutePath)) throw Exception("файлы голоса не найдены")
                            loadedModel = v.model
                        }
                        n.speak(text)
                    }
                } catch (e: Throwable) {
                    loadedModel = ""
                    runOnUiThread {
                        Toast.makeText(this, "Нейроголос не сработал (${e.message}). Использую системный.", Toast.LENGTH_LONG).show()
                        systemSpeak(text, l)
                    }
                }
            }
            return
        }
        systemSpeak(text, l)
    }

    private fun downloadVoice(v: Voice, then: (() -> Unit)? = null) {
        val k = "Голос: ${v.title}"
        if (tasks.containsKey(k)) return
        taskStart(k, 0, 1800000)
        thread {
            try {
                val arch = File(cacheDir, v.model + ".tar.bz2")
                val c = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${v.model}.tar.bz2")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    arch.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val p = (done * 90 / total).toInt()
                                if (p != last) { last = p; runOnUiThread { taskStart(k, p) } }
                            }
                        }
                    }
                }
                runOnUiThread { taskStart(k, 92) }
                val dir = voiceDir(v); dir.deleteRecursively(); dir.mkdirs()
                untarBz2(arch, dir); arch.delete()
                File(dir, ".ok").writeText("ok")
                runOnUiThread { taskDone(k, "Голос «${v.title}» установлен ✓ Он работает без интернета."); then?.invoke() }
            } catch (e: Throwable) {
                runOnUiThread { taskDone(k, "Не удалось скачать голос «${v.title}»: ${e.message}") }
            }
        }
    }

    private fun untarBz2(src: File, dest: File) {
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(src.inputStream().buffered())
        ).use { tis ->
            var e = tis.nextTarEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw Exception("bad archive")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { tis.copyTo(it) } }
                e = tis.nextTarEntry
            }
        }
    }

    private fun sample(l: Lang) { speak(sampleText[l.tag.substringBefore('-')] ?: "Test", l) }

    private fun showVoiceDialog() {
        val neuralOn = prefs.getString("tts", "neural") == "neural"
        val items = arrayOf(
            "Тип озвучки: " + if (neuralOn) "НЕЙРОСЕТЕВОЙ ГОЛОС, офлайн (переключить на системный)" else "СИСТЕМНЫЙ (переключить на нейросетевой)",
            "Выбрать голос…",
            "Прослушать текущий голос (${dst().name})")
        AlertDialog.Builder(this)
            .setTitle("Озвучка" + if (neural == null) " (нейроголоса недоступны в этой сборке)" else "")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> { prefs.edit().putString("tts", if (neuralOn) "system" else "neural").apply()
                           Toast.makeText(this, if (neuralOn) "Системная озвучка" else "Нейросетевой голос", Toast.LENGTH_SHORT).show()
                           wizardSeen.clear(); runSetup() }
                    1 -> pickVoiceLang()
                    2 -> sample(dst())
                }
            }.show()
    }

    private fun pickVoiceLang() {
        val ls = langs.filter { voices.containsKey(it.tag.substringBefore('-')) }
        val labels = ls.map { it.name + " — " + (selectedVoice(it)?.title ?: "") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Язык озвучки").setItems(labels) { _, i -> showVoices(ls[i]) }.show()
    }

    private fun showVoices(l: Lang) {
        val key = l.tag.substringBefore('-')
        val list = voices[key] ?: return
        val cur = list.indexOf(selectedVoice(l))
        val labels = list.map { it.title + if (voiceReady(it)) "   ✓ скачан" else "   — скачать (~70 МБ)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Голос: ${l.name}")
            .setSingleChoiceItems(labels, cur) { d, i ->
                val v = list[i]
                prefs.edit().putString("voice_$key", v.id).putString("tts", "neural").apply()
                d.dismiss()
                if (voiceReady(v)) sample(l) else downloadVoice(v) { sample(l) }
            }.setNegativeButton("Закрыть", null).show()
    }

    // ---------------------------------------------------------------- мастер настройки
    private val wizardSeen = HashSet<String>()
    private var dialogOpen = false
    private var resumeWizard = false

    private fun installed(pkg: String) =
        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun openPlay(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
        catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            && wizardSeen.add("mic")) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        if (!installed(GOOGLE) && ask("g", "Нужно приложение Google",
                "Для распознавания речи нужно приложение «Google». Открыть Google Play? Останется нажать «Установить».",
                "Да, открыть") { openPlay(GOOGLE) }) return
        val neuralOn = prefs.getString("tts", "neural") == "neural" && neural != null
        if (neuralOn) {
            for (l in listOf(src(), dst()).distinct()) {
                val v = selectedVoice(l) ?: continue
                if (!voiceReady(v) && ask("pv:" + v.id, "Нейросетевой голос",
                        "Скачать голос «${v.title}» для языка «${l.name}» (около 70 МБ)? Он работает без интернета.",
                        "Да, скачать") { downloadVoice(v) }) return
            }
        } else {
            if (!installed(GTTS) && ask("t", "Голоса Google",
                    "Для озвучки нужны «Службы речи от Google». Открыть Google Play? Останется нажать «Установить».",
                    "Да, открыть") { openPlay(GTTS) }) return
            if (ttsReady) {
                val miss = listOf(src(), dst()).filter {
                    tts?.isLanguageAvailable(Locale.forLanguageTag(it.tag)) == TextToSpeech.LANG_MISSING_DATA
                }
                if (miss.isNotEmpty() && ask("v:" + miss.joinToString { it.tag }, "Голоса для озвучки",
                        "Нужно скачать голоса: ${miss.joinToString { it.name }}. Открыть список голосов? Нажмите на язык и значок загрузки.",
                        "Да, открыть") { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }) return
            }
        }
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

    // ---------------------------------------------------------------- индикатор загрузок
    private val tasks = LinkedHashMap<String, Int>()
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
                if (tasks.containsKey(key)) taskDone(key, "$key: загрузка идёт в фоне.")
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

    // ---------------------------------------------------------------- обновление приложения
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

    private fun autoMaintenance() { if (prefs.getBoolean("auto", true)) checkUpdate(true) }

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

    // ---------------------------------------------------------------- меню, настройки, история
    private fun menu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "⚙ Настройки и обновления")
            menu.add(0, 2, 1, "Озвучка (голоса)")
            menu.add(0, 3, 2, "История")
            menu.add(0, 4, 3, "Избранное")
            menu.add(0, 5, 4, "Мастер настройки")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showSettings()
                    2 -> showVoiceDialog()
                    3 -> showHist(false)
                    4 -> showHist(true)
                    5 -> { wizardSeen.clear(); runSetup() }
                }
                true
            }
            show()
        }
    }

    private fun showSettings() {
        val auto = prefs.getBoolean("auto", true)
        val tt = if (prefs.getString("tts", "neural") == "neural") "нейросетевой" else "системный"
        val items = arrayOf(
            "Обновить приложение (версия ${BuildConfig.VERSION_CODE})",
            "Озвучка: $tt, голоса",
            "Автообновление при запуске: " + if (auto) "ВКЛ" else "ВЫКЛ",
            "Распознавание речи: " + if (prefs.getBoolean("sys", false)) "СИСТЕМНОЕ ОКНО (переключить на встроенное)" else "ВСТРОЕННОЕ (переключить на системное окно)",
            "Мастер настройки (Google, голоса)")
        AlertDialog.Builder(this).setTitle("Настройки").setItems(items) { _, i ->
            when (i) {
                0 -> checkUpdate(false)
                1 -> showVoiceDialog()
                2 -> { prefs.edit().putBoolean("auto", !auto).apply()
                       Toast.makeText(this, if (!auto) "Автообновление включено" else "Автообновление выключено", Toast.LENGTH_SHORT).show() }
                3 -> { val v = !prefs.getBoolean("sys", false); prefs.edit().putBoolean("sys", v).apply(); failCount = 0
                       Toast.makeText(this, if (v) "Системное окно голосового ввода" else "Встроенное распознавание", Toast.LENGTH_SHORT).show() }
                4 -> { wizardSeen.clear(); runSetup() }
            }
        }.show()
    }

    private fun loadHist(): MutableList<JSONObject> {
        val arr = JSONArray(prefs.getString("h", "[]"))
        return MutableList(arr.length()) { arr.getJSONObject(it) }
    }

    private fun saveHist(l: List<JSONObject>) { prefs.edit().putString("h", JSONArray(l).toString()).apply() }

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

    override fun onDestroy() {
        try { unregisterReceiver(installReceiver) } catch (e: Exception) { }
        try { neural?.release() } catch (e: Throwable) { }
        rec?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
