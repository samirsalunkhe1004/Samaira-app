package com.samaira.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var prefs: android.content.SharedPreferences

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private val messages = JSONArray()

    private val defaultSystemPrompt = """
You are Samaira, a warm, smart, witty personal voice assistant living on the user's Android phone.
Your replies are spoken aloud, so keep them short and natural, usually 1-2 sentences, no lists or symbols.
Reply in the language the user uses (English, Hindi, or Hinglish).
When asked to do something, use your tools to actually do it, then confirm briefly.
Before calls or messages, the phone opens pre-filled and the user taps the final button, so just do it.
Never handle payments, banking, or passwords. Answer general questions honestly; if unsure, use web_search.
""".trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("samaira", Context.MODE_PRIVATE)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)

        tts = TextToSpeech(this, this)
        resetConversation()

        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                handleUserText(text)
            }
        }
        findViewById<Button>(R.id.micBtn).setOnClickListener { startListening() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun resetConversation() {
        while (messages.length() > 0) messages.remove(0)
        val sys = prefs.getString("system_prompt", null)?.takeIf { it.isNotBlank() } ?: defaultSystemPrompt
        messages.put(JSONObject().put("role", "system").put("content", sys))
    }

    private fun handleUserText(text: String) {
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) {
            append("Samaira", "Please open Settings and paste your API key first.")
            return
        }
        append("You", text)
        messages.put(JSONObject().put("role", "user").put("content", text))

        val url = prefs.getString("api_url", "https://api.openai.com/v1/chat/completions")!!
        val model = prefs.getString("model", "gpt-4o-mini")!!

        thread {
            try {
                var steps = 0
                while (steps < 6) {
                    val msg = callApi(url, key, model, messages)
                    messages.put(msg)
                    val toolCalls = msg.optJSONArray("tool_calls")
                    if (toolCalls != null && toolCalls.length() > 0) {
                        for (i in 0 until toolCalls.length()) {
                            val call = toolCalls.getJSONObject(i)
                            val fn = call.getJSONObject("function")
                            val fname = fn.getString("name")
                            val argsStr = fn.optString("arguments", "{}")
                            val args = try { JSONObject(argsStr) } catch (e: Exception) { JSONObject() }
                            val result = runOnUiAndGet { runTool(fname, args) }
                            messages.put(
                                JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", call.getString("id"))
                                    .put("content", result)
                            )
                        }
                        steps++
                        continue
                    } else {
                        val content = msg.optString("content", "")
                        runOnUiThread {
                            append("Samaira", content)
                            speak(content)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { append("Samaira", "Something went wrong: ${e.message}") }
            }
        }
    }

    // ---------- LLM API ----------

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun callApi(url: String, key: String, model: String, msgs: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("tools", toolDefs)
            .put("tool_choice", "auto")
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw IOException("Empty response")
            if (!resp.isSuccessful) throw IOException("API error ${resp.code}: $text")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }
    }

    // ---------- Tools ----------

    private val toolDefs: JSONArray by lazy { JSONArray(TOOLS_JSON) }

    private fun runTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "make_call" -> {
                    val phone = args.getString("phone")
                    startI(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                    "Opened dialer for $phone."
                }
                "send_message" -> {
                    val appName = args.optString("app", "sms")
                    val phone = args.optString("phone", "")
                    val textMsg = args.optString("text", "")
                    if (appName.equals("whatsapp", true)) {
                        val num = phone.replace("+", "").replace(" ", "")
                        startI(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(textMsg))))
                        "Opened WhatsApp to $phone, pre-filled."
                    } else {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                        i.putExtra("sms_body", textMsg)
                        startI(i)
                        "Opened SMS to $phone, pre-filled."
                    }
                }
                "open_app" -> openApp(args.getString("query"))
                "web_search" -> {
                    val q = args.getString("query")
                    startI(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                    "Opened a web search for '$q'."
                }
                "set_alarm" -> {
                    val hour = args.getInt("hour")
                    val minute = args.optInt("minute", 0)
                    startI(Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("message", "Alarm"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    "Alarm set for %02d:%02d.".format(hour, minute)
                }
                "set_timer" -> {
                    val seconds = args.getInt("seconds")
                    startI(Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("message", "Timer"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    "Timer started for $seconds seconds."
                }
                "open_maps" -> {
                    val dest = args.getString("destination")
                    try {
                        startI(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
                            .setPackage("com.google.android.apps.maps"))
                    } catch (e: Exception) {
                        startI(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest))))
                    }
                    "Started navigation to $dest."
                }
                "flashlight" -> {
                    toggleTorch(args.optBoolean("on", true))
                    if (args.optBoolean("on", true)) "Flashlight on." else "Flashlight off."
                }
                "get_battery" -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    "Battery is at ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent."
                }
                "get_time" -> "Current time: " + SimpleDateFormat("EEEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Date()) + "."
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Could not do that ($name): ${e.message}"
        }
    }

    private fun startI(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openApp(query: String): String {
        val pm = packageManager
        for (app in pm.getInstalledApplications(0)) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.contains(query, ignoreCase = true)) {
                val launch = pm.getLaunchIntentForPackage(app.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    return "Opened $label."
                }
            }
        }
        return "Could not find an app called '$query'."
    }

    private fun toggleTorch(on: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                cm.setTorchMode(id, on)
                return
            }
        }
    }

    private fun <T> runOnUiAndGet(block: () -> T): T {
        var result: T? = null
        val lock = Object()
        var done = false
        runOnUiThread {
            synchronized(lock) {
                result = block()
                done = true
                lock.notifyAll()
            }
        }
        synchronized(lock) { while (!done) lock.wait() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun append(who: String, text: String) {
        transcript.append("\n\n$who: $text")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    private fun speak(text: String) {
        if (text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "samaira")
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) { Toast.makeText(this@MainActivity, "Listening…", Toast.LENGTH_SHORT).show() }
                override fun onResults(results: Bundle?) {
                    val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!said.isNullOrBlank()) handleUserText(said)
                }
                override fun onError(code: Int) { Toast.makeText(this@MainActivity, "Didn't catch that", Toast.LENGTH_SHORT).show() }
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        )
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            val e = EditText(this).apply { setText(value) }
            box.addView(e)
            return e
        }
        val urlE = field("API URL", prefs.getString("api_url", "https://api.openai.com/v1/chat/completions")!!)
        val keyE = field("API Key", prefs.getString("api_key", "")!!)
        val modelE = field("Model", prefs.getString("model", "gpt-4o-mini")!!)
        val promptE = field("Personality (system prompt)", prefs.getString("system_prompt", defaultSystemPrompt)!!)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_url", urlE.text.toString().trim())
                    .putString("api_key", keyE.text.toString().trim())
                    .putString("model", modelE.text.toString().trim())
                    .putString("system_prompt", promptE.text.toString().trim())
                    .apply()
                resetConversation()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TOOLS_JSON = """
[
  {"type":"function","function":{"name":"make_call","description":"Open the phone dialer pre-filled with a number.","parameters":{"type":"object","properties":{"phone":{"type":"string"}},"required":["phone"]}}},
  {"type":"function","function":{"name":"send_message","description":"Open SMS or WhatsApp pre-filled; user taps send.","parameters":{"type":"object","properties":{"app":{"type":"string","enum":["sms","whatsapp"]},"phone":{"type":"string"},"text":{"type":"string"}},"required":["app","phone","text"]}}},
  {"type":"function","function":{"name":"open_app","description":"Open an installed app by name.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}},
  {"type":"function","function":{"name":"web_search","description":"Open a Google web search.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}},
  {"type":"function","function":{"name":"set_alarm","description":"Set a phone alarm.","parameters":{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"},"message":{"type":"string"}},"required":["hour"]}}},
  {"type":"function","function":{"name":"set_timer","description":"Start a countdown timer.","parameters":{"type":"object","properties":{"seconds":{"type":"integer"},"message":{"type":"string"}},"required":["seconds"]}}},
  {"type":"function","function":{"name":"open_maps","description":"Start Google Maps navigation.","parameters":{"type":"object","properties":{"destination":{"type":"string"}},"required":["destination"]}}},
  {"type":"function","function":{"name":"flashlight","description":"Turn flashlight on or off.","parameters":{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"]}}},
  {"type":"function","function":{"name":"get_battery","description":"Get battery percentage.","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"get_time","description":"Get current date and time.","parameters":{"type":"object","properties":{}}}}
]
"""
    }
}
