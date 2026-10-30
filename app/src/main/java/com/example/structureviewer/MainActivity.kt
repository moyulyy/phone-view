package com.example.structureviewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var infoScroll: ScrollView
    private lateinit var infoContainer: LinearLayout
    private var viewerReady = false
    private var pending: Pair<String, String>? = null
    private var pendingUri: Uri? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleUri(uri)
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val u = pendingUri
            pendingUri = null
            if (u != null) {
                if (granted) handleUriInternal(u) else toast("缺少读取存储权限")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        infoScroll = findViewById(R.id.infoScroll)
        infoContainer = findViewById(R.id.infoContainer)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                viewerReady = true
                pending?.let { (d, f) ->
                    pending = null
                    doShow(d, f)
                }
            }
        }
        webView.loadUrl("file:///android_asset/viewer.html")

        findViewById<View>(R.id.openButton).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.clearButton).setOnClickListener { clearAll() }

        renderInfo(emptyList())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { handleUri(it) }
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val uri = stream ?: intent.clipData?.getItemAt(0)?.uri
                uri?.let { handleUri(it) }
            }
        }
    }

    private fun clearAll() {
        pending = null
        webView.evaluateJavascript("clearViewer()", null)
        renderInfo(emptyList())
    }

    private fun handleUri(uri: Uri) {
        if (uri.scheme == "file" && !hasStoragePermission()) {
            pendingUri = uri
            requestPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        handleUriInternal(uri)
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleUriInternal(uri: Uri) {
        Thread {
            try {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "unknown"
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    runOnUiThread { toast("文件为空或无法读取") }
                    return@Thread
                }

                val format = detectFormat(name, text)
                val rows: List<Pair<String, String>>
                val viewerData: String
                val viewerFormat: String

                when (format) {
                    "cif" -> {
                        val st = CifParser.parse(text)
                        rows = infoRows(st, name)
                        viewerData = text
                        viewerFormat = "cif"
                    }
                    "xyz" -> {
                        val st = XyzParser.parse(text)
                        rows = infoRows(st, name)
                        viewerData = text
                        viewerFormat = "xyz"
                    }
                    "vasp" -> {
                        val st = VaspParser.parse(text)
                        rows = infoRows(st, name)
                        viewerData = CifExporter.toCif(st)
                        viewerFormat = "cif"
                    }
                    else -> {
                        runOnUiThread { toast("无法识别的文件格式（支持 POSCAR / CONTCAR / CIF / XYZ）") }
                        return@Thread
                    }
                }

                runOnUiThread {
                    renderInfo(rows)
                    doShow(viewerData, viewerFormat)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("解析失败: ${e.message}") }
                e.printStackTrace()
            }
        }.start()
    }

    private fun doShow(data: String, format: String) {
        if (!viewerReady) {
            pending = data to format
            return
        }
        val json = JSONObject.quote(data)
        webView.evaluateJavascript("show($json, '$format')", null)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            var name: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
            name
        } catch (e: Exception) {
            null
        }
    }

    private fun detectFormat(name: String, text: String): String {
        val lower = name.lowercase(Locale.US)
        if (lower.endsWith(".cif")) return "cif"
        if (lower.endsWith(".xyz")) return "xyz"
        if (lower.endsWith(".poscar") || lower.endsWith(".contcar") || lower.endsWith(".vasp")) {
            return "vasp"
        }
        if (name.equals("POSCAR", true) || name.equals("CONTCAR", true)) return "vasp"

        val t = text.trim()
        if (t.startsWith("data_")) return "cif"

        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val firstTok = lines.firstOrNull()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        if (firstTok.toIntOrNull() != null) return "xyz"

        if (lines.size >= 5) {
            val ok = listOf(2, 3, 4).all { idx ->
                val toks = lines[idx].split(Regex("\\s+")).filter { it.isNotEmpty() }
                toks.size >= 3 && toks.take(3).all { it.toDoubleOrNull() != null }
            }
            if (ok) return "vasp"
        }
        return "unknown"
    }

    private fun infoRows(s: Structure, name: String): List<Pair<String, String>> {
        val atomList = if (s.atoms.isNotEmpty()) s.atoms else (s.frac ?: emptyList())
        val rows = mutableListOf<Pair<String, String>>()
        rows.add("文件" to name)
        rows.add("格式" to s.format)
        if (s.title.isNotBlank()) rows.add("名称" to s.title)
        rows.add("原子数" to atomList.size.toString())

        val comp = LinkedHashMap<String, Int>()
        for (a in atomList) comp[a.element] = (comp[a.element] ?: 0) + 1
        if (comp.isNotEmpty()) {
            rows.add("组成" to comp.entries.joinToString("   ") { "${it.key} ${it.value}" })
        }
        s.cell?.let {
            rows.add("晶胞" to String.format(Locale.US, "%.4f × %.4f × %.4f Å", it.a, it.b, it.c))
            rows.add("角度" to String.format(Locale.US, "%.2f° · %.2f° · %.2f°", it.alpha, it.beta, it.gamma))
            rows.add("体积" to String.format(Locale.US, "%.3f Å³", it.volume))
        }
        return rows
    }

    // ---------- iOS 风格信息卡片 ----------

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun renderInfo(rows: List<Pair<String, String>>) {
        infoContainer.removeAllViews()
        if (rows.isEmpty()) {
            val hint = TextView(this).apply {
                text = getString(R.string.welcome)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary_label))
                setLineSpacing(dp(3f).toFloat(), 1f)
                setPadding(0, dp(10f), 0, dp(10f))
            }
            infoContainer.addView(hint)
        } else {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) infoContainer.addView(makeDivider())
                infoContainer.addView(makeInfoRow(label, value))
            }
        }
        capInfoHeight()
    }

    private fun makeInfoRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, dp(9f))
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary_label))
        }
        val valueView = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.label))
            gravity = Gravity.END
            setLineSpacing(dp(2f).toFloat(), 1f)
        }
        row.addView(labelView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        row.addView(valueView, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = dp(12f) })
        return row
    }

    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
        )
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.separator))
    }

    /** 限制信息卡片最大高度，避免挤压 3D 视图 */
    private fun capInfoHeight() {
        val lp = infoScroll.layoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        infoScroll.layoutParams = lp
        infoScroll.post {
            val child = infoScroll.getChildAt(0) ?: return@post
            val width = if (infoScroll.width > 0) infoScroll.width
            else resources.displayMetrics.widthPixels - dp(32f)
            child.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED
            )
            val maxPx = (resources.displayMetrics.heightPixels * 0.30f).toInt()
            val newLp = infoScroll.layoutParams
            newLp.height = if (child.measuredHeight > maxPx) maxPx
            else ViewGroup.LayoutParams.WRAP_CONTENT
            infoScroll.layoutParams = newLp
        }
    }
}
