package ir.sadegh.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFa = false
    private var running = false
    private var speaking = false
    private var awaitingUntil = 0L
    private val pending = HashMap<String, (() -> Unit)?>()
    private val restartRun = Runnable { listen() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!running) { running = true; initTts(); listen() }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sadegh", "Sadegh", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sadegh")
            .setContentTitle("صادق در حال گوش دادن است")
            .setContentText("بگو «صادق»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            val t = tts
            if (st == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale("fa", "IR"))
                ttsFa = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsFa) t.setLanguage(Locale.US)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finished(id) }
                    override fun onError(id: String?) { finished(id) }
                })
                ttsReady = true
            }
        }
    }

    private fun finished(id: String?) = h.post {
        speaking = false
        val a = pending.remove(id)
        a?.invoke()
        restart(300)
    }

    private fun say(faText: String, enText: String, after: (() -> Unit)? = null) {
        val t = tts
        if (!ttsReady || t == null) { after?.invoke(); restart(300); return }
        speaking = true
        rec?.cancel()
        val id = System.nanoTime().toString()
        pending[id] = after
        t.speak(if (ttsFa) faText else enText, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun restart(ms: Long) { h.removeCallbacks(restartRun); h.postDelayed(restartRun, ms) }

    private fun listen() {
        if (!running || speaking) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= 33)
                putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayOf("en-US"))
        }
        rec?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
        override fun onError(e: Int) {
            val slow = e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_NETWORK ||
                e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || e == SpeechRecognizer.ERROR_SERVER
            restart(if (slow) 1500 else 250)
        }
        override fun onResults(b: Bundle?) {
            val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (l.isNullOrEmpty()) restart(250) else handle(l)
        }
    }

    private fun handle(raw: List<String>) {
        val cands = raw.map { Text.norm(it) }.filter { it.isNotBlank() }
        if (SystemClock.elapsedRealtime() < awaitingUntil) { awaitingUntil = 0; execute(cands); return }
        val withWake = cands.filter { c -> c.split(" ").any { Text.isWake(it) } }
        if (withWake.isEmpty()) { restart(150); return }
        val hasCmd = withWake.any { c -> c.split(" ").any { !Text.isWake(it) } }
        if (!hasCmd) {
            awaitingUntil = SystemClock.elapsedRealtime() + 10000
            say("بله؟", "Yes?")
        } else execute(withWake)
    }

    private fun execute(cands: List<String>) {
        when (val c = Parser.parse(cands)) {
            is Cmd.Call -> doCall(c.targets)
            is Cmd.Open -> doOpen(c.targets)
            Cmd.Time -> {
                val t = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                say("ساعت $t است", "It's $t")
            }
            Cmd.Unknown -> say("متوجه نشدم. مثلاً بگو: صادق، کال مامان", "Sorry, I didn't get that")
        }
    }

    // ---------- تماس ----------
    private fun loadContacts(): LinkedHashMap<String, String>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val m = LinkedHashMap<String, String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val p = c.getString(1) ?: continue
                if (!m.containsKey(n)) m[n] = p
            }
        }
        return m
    }

    private fun doCall(targets: List<String>) {
        if (targets.isEmpty()) { say("با کی تماس بگیرم؟ مثلاً بگو: صادق، کال مامان", "Who should I call?"); return }
        val contacts = loadContacts()
        if (contacts == null) { say("دسترسی به مخاطبین را نداری", "No contacts permission"); return }
        val scored = contacts.entries.map { e -> e to targets.maxOf { Match.score(it, e.key) } }
            .sortedByDescending { it.second }
        val top = scored.firstOrNull()
        if (top == null || top.second < 0.8) {
            say("مخاطبی با این نام پیدا نکردم", "I couldn't find that contact"); return
        }
        val second = scored.getOrNull(1)
        if (second != null && top.second < 1.0 && second.second >= top.second - 0.03) {
            val a = top.first.key; val b = second.first.key
            say("چند مخاطب شبیه هم هستند: $a یا $b. اسم دقیق‌تر بگو", "Several matches: $a or $b"); return
        }
        val name = top.first.key; val num = top.first.value
        say("در حال تماس با $name", "Calling $name") { dial(num) }
    }

    private fun dial(num: String) {
        val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", num, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { say("نتوانستم تماس بگیرم", "I couldn't place the call") }
    }

    // ---------- باز کردن برنامه ----------
    private val alias = mapOf(
        "دوربین" to "camera", "تنظیمات" to "settings", "گالری" to "gallery", "عکس ها" to "photos",
        "مرورگر" to "chrome", "نقشه" to "maps", "مخاطبین" to "contacts", "پیام ها" to "messages",
        "ماشین حساب" to "calculator", "تقویم" to "calendar", "ساعت" to "clock", "ایمیل" to "gmail"
    )

    private fun doOpen(targets: List<String>) {
        if (targets.isEmpty()) { say("کدام برنامه را باز کنم؟", "Which app?"); return }
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        var best: Pair<String, String>? = null; var bs = 0.0
        for (a in apps) for (t in targets) for (q in listOfNotNull(t, alias[t])) {
            val s = Match.score(q, a.first)
            if (s > bs) { bs = s; best = a }
        }
        val b = best
        if (b == null || bs < 0.78) { say("برنامه‌ای با این نام پیدا نکردم", "I couldn't find that app"); return }
        val li = pm.getLaunchIntentForPackage(b.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (li == null) { say("نتوانستم باز کنم", "I couldn't open it"); return }
        say("در حال باز کردن ${b.first}", "Opening ${b.first}") {
            try { startActivity(li) } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        rec?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
