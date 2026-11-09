package ru.bpdiary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.bpdiary.ble.TonometerLink
import ru.bpdiary.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TonometerLink.init(this)
        setContent { App() }
    }

    // Тонометр слушаем только пока приложение на экране
    override fun onStart() {
        super.onStart()
        TonometerLink.setActive(true)
    }

    override fun onStop() {
        TonometerLink.setActive(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        TonometerLink.restart() // например, после выдачи разрешений или включения Bluetooth
    }
}
