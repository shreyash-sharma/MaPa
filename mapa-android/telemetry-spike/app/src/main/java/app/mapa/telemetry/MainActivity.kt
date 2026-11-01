package app.mapa.telemetry

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Single screen, built in code to keep the APK tiny. Two modes:
 * consent (nothing is collected yet) and test dashboard.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun render() {
        root.removeAllViews()
        status = null
        heading("MaPa phone activity test")
        if (prefs.consented) renderDashboard() else renderConsent()
    }

    private fun renderConsent() {
        text(CONSENT)
        val agree = CheckBox(this).apply { text = "I own this phone and I agree to run this test." }
        root.addView(agree)
        button("Start test") {
            if (!agree.isChecked) {
                toast("Please tick the box first.")
                return@button
            }
            prefs.consented = true
            EventLog.append(this, "consent_granted", JSONObject().put("model", Device.model()))
            Collector.start(this)
            render()
        }
    }

    private fun renderDashboard() {
        status = text("").apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }

        heading("Setup")
        button("1. Allow Usage Access") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        button("2. Battery: don't optimise this app") { requestBatteryExemption() }
        button("3. Phone maker's auto-start settings") { OemSettings.open(this) }
        button("App info") { OemSettings.openAppInfo(this) }

        heading("Test")
        button("Record a heartbeat now") {
            thread {
                Heartbeat.run(applicationContext, "manual")
                runOnUiThread { refresh() }
            }
        }
        button(if (prefs.paused) "Resume sharing" else "Pause sharing") {
            prefs.paused = !prefs.paused
            EventLog.append(this, "pause_changed", JSONObject().put("paused", prefs.paused))
            render()
        }

        text("Optional sync URL (see README). Leave empty to keep everything on this phone.")
        val url = EditText(this).apply {
            setText(prefs.endpoint)
            hint = "https://…/v1/heartbeat"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        root.addView(url)
        button("Save sync URL") {
            prefs.endpoint = url.text.toString()
            EventLog.append(this, "endpoint_changed", JSONObject().put("enabled", prefs.endpoint.isNotBlank()))
            toast(if (prefs.endpoint.isBlank()) "Sync off" else "Sync URL saved")
        }

        heading("Results")
        button("Share test log") { exportLog() }
        button("Stop test and delete data") { confirmStop() }
        refresh()
    }

    private fun refresh() {
        val view = status ?: return
        val snap = Device.snapshot(this)
        val report = Report.summarize(EventLog.readAll(this), System.currentTimeMillis())
        view.text = buildString {
            appendLine("Device: ${Device.model()}  (Android SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Sharing: ${if (prefs.paused) "PAUSED" else "on"}")
            appendLine("Usage Access: ${if (snap.optBoolean("usageAccess")) "granted" else "NOT GRANTED"}")
            appendLine("Battery optimisation: ${if (snap.optBoolean("ignoringBatteryOpt")) "off for this app" else "on (default)"}")
            if (snap.has("standbyBucket")) appendLine("Standby bucket: ${snap.optString("standbyBucket")}")
            if (snap.optBoolean("backgroundRestricted")) appendLine("Background: RESTRICTED by user/OEM")
            appendLine("Battery: ${snap.optInt("battery")}%  plugged: ${snap.optString("plugged")}")
            appendLine("Network: ${snap.optString("network")}")
            appendLine()
            append(report)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun exportLog() {
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = "mapa-${Device.model().replace(Regex("[^A-Za-z0-9]+"), "_")}-$stamp.jsonl"
        val out = File(dir, name)
        EventLog.copyTo(this, out)
        val uri = FileProvider.getUriForFile(this, "$packageName.export", out)
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, "Share test log"))
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop the test?")
            .setMessage("This stops all collection on this phone and deletes the local log. Export it first if you still need it.")
            .setPositiveButton("Stop and delete") { _, _ ->
                Collector.stop(this)
                EventLog.clear(this)
                prefs.clearAll()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun heading(s: String) = text(s).apply {
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
        root.addView(this)
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
        root.addView(this)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val CONSENT = """
            This is a test build of MaPa. It checks whether this phone can reliably report that it is being used.

            What it records, on this phone only:
            • When the phone was unlocked or an app was opened (times only, never which app)
            • When the screen turned on or off
            • Battery level and whether it is charging
            • Whether Android paused or stopped this test app, and when the phone restarted

            What it never records:
            • Location, camera, microphone, contacts, calls or messages
            • Names of apps you use, or anything shown on screen

            Nothing is sent anywhere unless a sync address is added later. You can pause, share the log yourself, or stop and delete everything at any time.
        """.trimIndent()
    }
}
