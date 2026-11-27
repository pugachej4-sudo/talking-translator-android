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
        if (listeningSide == side) { rec?.stopListening(); return }
        if (listeningSide != -1) return
        if (!retry) retryLeft = 1
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSide = side
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
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
                        if (Build.VERSION.SDK_INT < 33)
                            botTv.text = "Нужен речевой пакет «${a.name}»: Настройки → Google → Голосовой ввод → Офлайн-распознавание."
                        return
                    }
                    if ((code == 7 || code == 6) && retryLeft > 0) {
                        retryLeft--
                        botTv.text = "Слушаю…"
                        ui.postDelayed({ if (listeningSide == -1) listen(side, true) }, 300)
                        return
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
    private fun newRecognizer(): SpeechRecognizer {
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
                val k = "Язык перевода: ${l.name}"
                taskStart(k)
                mm.download(m, DownloadConditions.Builder().build())
                    .addOnSuccessListener { taskDone(k, "Язык «${l.name}» скачан ✓") }
                    .addOnFailureListener { taskDone(k, "Не удалось скачать «${l.name}». Нужен интернет.") }
            }
        }
    }

    // 2) офлайн-пакет распознавания речи (Android 13+)
    private fun ensureSpeech(l: Lang, force: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val sp0 = getSharedPreferences("tt", MODE_PRIVATE)
        val spKey = "sp:" + l.tag
        if (!force) {
            if (!prepared.add(spKey)) return
            if (System.currentTimeMillis() - sp0.getLong(spKey, 0) < 3L * 24 * 3600 * 1000) return
        }
        try {
            val r = newRecognizer()
            helpers.add(r)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, l.tag)
            val pre = l.tag.substringBefore('-')
            fun List<String>.has() = any { it.equals(l.tag, true) || it.substringBefore('-').equals(pre, true) }
            r.checkRecognitionSupport(i, mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val installed = s.installedOnDeviceLanguages.has()
                    val pending = s.pendingOnDeviceLanguages.has()
                    val supported = s.supportedOnDeviceLanguages.has()
                    if (!installed && !pending && supported) {
                        val k = "Речевой пакет: ${l.name}"
                        sp0.edit().putLong(spKey, System.currentTimeMillis()).apply()
                        taskStart(k, -1, 60000)
                        try {
                            if (Build.VERSION.SDK_INT >= 34) {
                                r.triggerModelDownload(i, mainExecutor, object : ModelDownloadListener {
                                    override fun onProgress(completedPercent: Int) { taskStart(k, completedPercent) }
                                    override fun onSuccess() { taskDone(k, "Речевой пакет «${l.name}» скачан ✓") }
                                    override fun onScheduled() { taskStart(k, -1) }
                                    override fun onError(error: Int) {
                                        taskDone(k, "Речевой пакет «${l.name}» не скачался (код $error). Онлайн-режим работает и без него.")
                                    }
                                })
                            } else r.triggerModelDownload(i)
                        } catch (e: Exception) { taskDone(k, "Речевой пакет «${l.name}» скачать не удалось. Онлайн-режим работает и без него.") }
                    }
                }
                override fun onError(error: Int) {}
            })
        } catch (e: Exception) { }
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
        prepared.clear(); ensureSpeech(src(), true); ensureSpeech(dst(), true)
        getSharedPreferences("tt", MODE_PRIVATE).edit().putLong("langUpd", System.currentTimeMillis()).apply()
    }

    private fun showSettings() {
        val sp = getSharedPreferences("tt", MODE_PRIVATE)
        val auto = sp.getBoolean("auto", true)
        val items = arrayOf(
            "Обновить приложение (версия ${BuildConfig.VERSION_CODE})",
            "Обновить языковые пакеты",
            "Автообновление при запуске: " + if (auto) "ВКЛ" else "ВЫКЛ",
            "Режим: " + if (online) "ОНЛАЙН (переключить на офлайн)" else "ОФЛАЙН (переключить на онлайн)",
            "Мастер настройки (Google, голоса)",
            "Распознавание речи: " + if (sp.getBoolean("sys", false)) "СИСТЕМНОЕ ОКНО (переключить на встроенное)" else "ВСТРОЕННОЕ (переключить на системное окно)")
        AlertDialog.Builder(this).setTitle("Настройки").setItems(items) { _, i ->
            when (i) {
                0 -> checkUpdate(false)
                1 -> updateLangs(false)
                2 -> { sp.edit().putBoolean("auto", !auto).apply()
                       Toast.makeText(this, if (!auto) "Автообновление включено" else "Автообновление выключено", Toast.LENGTH_SHORT).show() }
                3 -> toggleMode()
                4 -> { wizardSeen.clear(); prepared.clear(); ensureLang(src()); ensureLang(dst()); runSetup() }
                5 -> { val v = !sp.getBoolean("sys", false); sp.edit().putBoolean("sys", v).apply(); failCount = 0
                       Toast.makeText(this, if (v) "Системное окно голосового ввода" else "Встроенное распознавание", Toast.LENGTH_SHORT).show() }
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
        1, 2 -> "Проблема с сетью. Проверьте интернет."
        3 -> "Ошибка записи звука."
        8 -> "Распознаватель занят, повторите."
        9 -> "Нет разрешения на микрофон."
        else -> "Ошибка распознавания ($c)."
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

    private fun toggleMode() {
        online = !online
        Toast.makeText(this, if (online) "Онлайн" else "Офлайн", Toast.LENGTH_SHORT).show()
        if (!online) { ensureSpeech(src(), true); ensureSpeech(dst(), true) }   // для офлайна нужны речевые пакеты
    }

    // ---------- индикатор загрузок: «Подождите, пожалуйста» + полоса % ----------
    private val tasks = LinkedHashMap<String, Int>()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private fun taskStart(key: String, pct: Int = -1, timeoutMs: Long = 180000) {
        val first = !tasks.containsKey(key)
        tasks[key] = pct
        if (first) ui.postDelayed({
            if (tasks.containsKey(key))
                taskDone(key, "$key: загрузка идёт в фоне. Онлайн-режим уже работает, ждать не нужно.")
        }, timeoutMs)
        showTasks()
    }

    private fun taskDone(key: String, msg: String) {
        tasks.remove(key)
        if (tasks.isEmpty()) { pb.visibility = View.GONE; botTv.text = msg } else showTasks()
    }

    private fun showTasks() {
        botTv.text = tasks.entries.joinToString("\n") { it.key + if (it.value >= 0) " — ${it.value}%" else "…" } +
            "\n\nПодождите, пожалуйста"
        pb.visibility = View.VISIBLE
        val p = tasks.values.firstOrNull { it >= 0 }
        if (p == null) pb.isIndeterminate = true else { pb.isIndeterminate = false; pb.progress = p }
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
            menu.add(0, 6, 0, "⚙ Настройки и обновления")
            menu.add(0, 1, 0, if (online) "Режим: ОНЛАЙН (нажмите — офлайн)" else "Режим: ОФЛАЙН (нажмите — онлайн)")
            menu.add(0, 2, 1, "История")
            menu.add(0, 3, 2, "Избранное")
            menu.add(0, 4, 3, "Скачать языки для офлайна")
            menu.add(0, 5, 4, "Мастер настройки")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> toggleMode()
                    2 -> showHist(false)
                    3 -> showHist(true)
                    4 -> downloadLangs()
                    6 -> showSettings()
                    5 -> { wizardSeen.clear(); prepared.clear(); ensureLang(src()); ensureLang(dst()); runSetup() }
                }
                true
            }
            show()
        }
    }

    private fun downloadLangs() {
        setOf(src(), dst()).forEach { l ->
            val k = "Язык перевода: ${l.name}"
            taskStart(k)
            RemoteModelManager.getInstance()
                .download(TranslateRemoteModel.Builder(l.ml).build(), DownloadConditions.Builder().build())
                .addOnSuccessListener { taskDone(k, "Языки скачаны ✓ Перевод работает без интернета.") }
                .addOnFailureListener { taskDone(k, "Ошибка загрузки. Нужен интернет.") }
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

    override fun onDestroy() { try { unregisterReceiver(installReceiver) } catch (e: Exception) { }; helpers.forEach { it.destroy() }; rec?.destroy(); tts?.shutdown(); super.onDestroy() }
}
