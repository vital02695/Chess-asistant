package com.example.chessassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true)
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sf = java.io.File(applicationInfo.nativeLibraryDir, "libstockfish.so").exists()
        val info = TextView(this).apply {
            textSize = 16f
            text = "Chess Assistant\n\n" +
                (if (sf) "Stockfish: найден ✓\n\n" else "Stockfish: НЕ найден ✗\nПоложите libstockfish.so в app/src/main/jniLibs/arm64-v8a/ и пересоберите (см. README). Без него доска распознаётся, но ходы не считаются.\n\n") +
                "1. Нажмите «Запустить» и дайте разрешения (поверх других окон, запись экрана).\n" +
                "2. Откройте вашу шахматную страницу.\n" +
                "3. В начальной позиции нажмите «Белые↓ (кал.)» или «Чёрные↓ (кал.)» — " +
                "в зависимости от того, кто снизу. Это нужно один раз.\n" +
                "4. Зелёная стрелка — ваш лучший ход, красные — вероятные ответы соперника с процентами."
        }
        val btn = Button(this).apply {
            text = "Запустить"
            setOnClickListener { start() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 48, 48, 48)
            addView(info)
            addView(btn)
        })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешите «Поверх других окон» и вернитесь", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
