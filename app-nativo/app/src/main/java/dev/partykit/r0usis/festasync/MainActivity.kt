package dev.partykit.r0usis.festasync

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.partykit.r0usis.festasync.net.SERVER_HOST
import dev.partykit.r0usis.festasync.net.sanitizeRoomCode
import dev.partykit.r0usis.festasync.ui.FestaTheme
import dev.partykit.r0usis.festasync.ui.JoinScreen
import dev.partykit.r0usis.festasync.ui.RoomScreen

class MainActivity : ComponentActivity() {
    private val vm: PartyViewModel by viewModels()
    // código da sala vindo de um link (https://festa-sync.../?room=XYZ) — já preenche o campo
    private var linkRoom by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // fundo sempre escuro: ícones da barra de status (hora, bateria) em branco
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        linkRoom = roomFromIntent(intent)
        setContent {
            FestaTheme {
                LaunchedEffect(Unit) {
                    vm.toasts.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                }
                // festa com música tocando: a tela não apaga sozinha enquanto está numa sala
                LaunchedEffect(vm.screen) {
                    if (vm.screen == PartyViewModel.Screen.Room) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                when (vm.screen) {
                    PartyViewModel.Screen.Join -> JoinScreen(vm, linkRoom)
                    // "voltar" na aba Música não sai da festa: só manda o app pra segundo plano
                    PartyViewModel.Screen.Room -> RoomScreen(vm, onBackground = { moveTaskToBack(true) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        roomFromIntent(intent)?.let { linkRoom = it }
    }

    private fun roomFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.host != SERVER_HOST) return null
        return data.getQueryParameter("room")?.let(::sanitizeRoomCode)?.takeIf { it.isNotEmpty() }
    }
}
