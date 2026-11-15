package com.example.chessassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class CaptureService : Service() {

    private val levels = listOf("новичок" to 150, "средний" to 90, "сильный" to 40)

    private lateinit var wm: WindowManager
    private lateinit var overlay: OverlayView
    private lateinit var panel: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var btnTurn: Button
    private lateinit var btnLevel: Button
    private lateinit var btnAuto: Button
    private lateinit var btnBadges: Button
    private lateinit var recognizer: BoardRecognizer
    private lateinit var engine: StockfishEngine
    private lateinit var analyzer: Analyzer
    private lateinit var thread: HandlerThread
    private lateinit var bg: Handler
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var width = 0
    private var height = 0

    @Volatile private var running = false
    @Volatile private var whiteAtBottom = true   // игрок «снизу» = «я»
    @Volatile private var whiteToMove = true
    @Volatile private var level = 1
    @Volatile private var auto = true
    @Volatile private var scanNow = false
    @Volatile private var settled = false
    @Volatile private var showBadges = true
    @Volatile private var calibrateAs: Boolean? = null
    @Volatile private var analyzedKey: String? = null

    private var pendingKey: String? = null
    private var stable = 0
    private var prevBoard: Array<CharArray>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        startForegroundCompat()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recognizer = BoardRecognizer(this)
        engine = StockfishEngine(this)
        analyzer = Analyzer(engine)
        readScreenSize()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        // Android 14+: колбэк нужно зарегистрировать до создания VirtualDisplay
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val rd = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = rd
        display = proj.createVirtualDisplay(
            "chess-capture", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )

        buildViews()

        thread = HandlerThread("capture").also { it.start() }
        bg = Handler(thread.looper)
        running = true
        bg.post { loop() }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Chess Assistant", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "cap")
            .setContentTitle("Chess Assistant работает")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            width = b.width(); height = b.height()
        } else {
            val pt = Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(pt)
            width = pt.x; height = pt.y
        }
    }

    // ---------- окна поверх экрана ----------

    private fun buildViews() {
        overlay = OverlayView(this)
        val op = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(overlay, op)

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(16, 8, 16, 8)
            maxWidth = (width * 0.9f).toInt()
            text = "≡ тяните за этот текст · ищу доску…"
        }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(14, 6, 14, 6)
            setOnClickListener { onClick() }
        }
        fun row(vararg b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEach { addView(it) }
        }

        btnTurn = btn("") {
            whiteToMove = !whiteToMove; analyzedKey = null; scanNow = true; refreshButtons()
        }
        btnLevel = btn("") {
            level = (level + 1) % levels.size; analyzedKey = null; scanNow = true; refreshButtons()
        }
        btnAuto = btn("") { auto = !auto; refreshButtons() }
        btnBadges = btn("") { showBadges = !showBadges; analyzedKey = null; scanNow = true; refreshButtons() }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(
                btn("Белые↓ (кал.)") { calibrateAs = true; scanNow = true },
                btn("Чёрные↓ (кал.)") { calibrateAs = false; scanNow = true },
                btn("Флип") { whiteAtBottom = !whiteAtBottom; scanNow = true }
            ))
            addView(row(btnTurn, btnLevel, btnAuto))
            addView(row(
                btn("Скан") { scanNow = true },
                btnBadges,
                btn("Стоп") { stopSelf() }
            ))
        }
        val toggle = btn("▼") {}.apply {
            setOnClickListener {
                val show = controls.visibility != View.VISIBLE
                controls.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "▼" else "▲"
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statusView)
            addView(toggle)
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD202020.toInt())
            addView(header)
            addView(controls)
        }
        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 120 }
        wm.addView(panel, pp)

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        statusView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = pp.x; sy = pp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    pp.x = sx + (e.rawX - tx).toInt()
                    pp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(panel, pp)
                }
            }
            true
        }
        refreshButtons()
    }

    private fun refreshButtons() {
        main.post {
            btnTurn.text = if (whiteToMove) "Ход: белых" else "Ход: чёрных"
            btnLevel.text = "Соперник: ${levels[level].first}"
            btnAuto.text = if (auto) "Авто ✓" else "Авто ✗"
            btnBadges.text = if (showBadges) "Бейджи ✓" else "Бейджи ✗"
        }
    }

    private fun say(t: String) { main.post { statusView.text = t } }

    private fun publishBoard(rect: BoardRect?, grid: Array<CharArray>?) {
        main.post {
            overlay.rect = rect
            overlay.grid = if (showBadges) grid else null
            overlay.invalidate()
        }
    }

    private fun clearAnalysis() {
        analyzer.cancel()
        main.post {
            overlay.arrows = emptyList()
            overlay.evalWhiteCp = null
            overlay.mateWhite = null
            overlay.invalidate()
        }
    }

    // ---------- цикл захвата ----------

    // Оверлей попадает в захват экрана, поэтому перед кадром его прячем (если он что-то рисует).
    private fun loop() {
        if (!running) return
        if (!auto && !scanNow) { bg.postDelayed({ loop() }, 300); return }
        scanNow = false
        val hide = overlay.hasContent()
        if (hide) main.post { overlay.visibility = View.INVISIBLE }
        bg.postDelayed({
            if (!running) return@postDelayed
            try { process() } catch (e: Exception) { say("Ошибка: ${e.message}") }
            main.post { overlay.visibility = View.VISIBLE }
            bg.postDelayed({ loop() }, if (settled) 1500L else 450L)
        }, if (hide) 250L else 100L)
    }

    private fun grabFrame(): Bitmap? {
        val img = reader?.acquireLatestImage()
        if (img != null) {
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val bw = width + rowPadding / plane.pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != bw || bmp.height != height) {
                bmp = Bitmap.createBitmap(bw, height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            img.close()
        }
        return bitmap // если нового кадра нет (экран не менялся), берём предыдущий
    }

    private fun process() {
        val bmp = grabFrame()
        if (bmp == null) { say("Нет кадра"); return }
        val w = bmp.width
        val h = bmp.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val rect = recognizer.detect(pixels, w, h)
        if (rect == null) {
            settled = false
            publishBoard(null, null)
            say("Доска не найдена")
            return
        }

        val cal = calibrateAs
        if (cal != null) {
            calibrateAs = null
            if (recognizer.calibrate(pixels, w, rect, cal)) {
                whiteAtBottom = cal
                whiteToMove = true
                pendingKey = null; analyzedKey = null; prevBoard = null; stable = 0
                clearAnalysis()
                refreshButtons()
                say("Калибровка выполнена")
            } else {
                say("Калибровка не удалась: на экране должна быть начальная позиция")
                return
            }
        }
        if (!recognizer.hasTemplates) {
            publishBoard(rect, null)
            say("Доска найдена. В начальной позиции нажмите «Белые↓» или «Чёрные↓» (кал.)")
            return
        }

        val grid = recognizer.readGrid(pixels, w, rect)
        publishBoard(rect, grid)
        val key = grid.joinToString("|") { String(it) } + if (whiteAtBottom) "W" else "B"
        if (key == pendingKey) stable++ else {
            pendingKey = key
            stable = 1
            settled = false
            if (key != analyzedKey) clearAnalysis()
        }
        // позиция должна продержаться два скана подряд (чтобы не ловить анимацию хода)
        if (stable >= 2 && key != analyzedKey) {
            analyzedKey = key
            onStable(grid)
        }
    }

    private fun onStable(grid: Array<CharArray>) {
        if (Rules.hasUnknown(grid)) {
            val n = grid.sumOf { r -> r.count { it == '?' } }
            say("Не распознано клеток: $n. Откалибруйте заново в начальной позиции.")
            return
        }
        val board = Rules.toBoard(grid, whiteAtBottom)
        if (!Rules.valid(board)) {
            say("Позиция невалидна (проверьте сторону «Флип» и калибровку)")
            return
        }
        if (Rules.isStart(board)) whiteToMove = true
        else prevBoard?.let { p -> Rules.moverOf(p, board)?.let { whiteToMove = !it } }
        prevBoard = board
        refreshButtons()

        val fen = Rules.fen(board, whiteToMove)
        say("Думаю…\n$fen")
        analyzer.request(
            board, fen, whiteToMove, whiteAtBottom, whiteAtBottom, levels[level].second,
            onResult = { res ->
                main.post {
                    overlay.arrows = res.arrows
                    overlay.evalWhiteCp = res.whiteCp
                    overlay.mateWhite = res.whiteMate
                    overlay.invalidate()
                    statusView.text = res.text
                }
                settled = true
            },
            onStatus = { say(it) }
        )
    }

    override fun onDestroy() {
        running = false
        try { if (::overlay.isInitialized) wm.removeView(overlay) } catch (_: Exception) {}
        try { if (::panel.isInitialized) wm.removeView(panel) } catch (_: Exception) {}
        if (::analyzer.isInitialized) analyzer.shutdown()
        display?.release()
        reader?.close()
        projection?.stop()
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }
}
