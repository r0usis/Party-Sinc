package dev.partykit.r0usis.festasync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.Member
import dev.partykit.r0usis.festasync.net.avatarFor

/** Botão do microfone. Pede a permissão na primeira vez; o anel cresce com o volume da voz
 *  (igual o site), pra dar confiança de que o som está mesmo saindo. */
@Composable
fun MicButton(vm: PartyViewModel, size: Dp = 40.dp) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleMic()
        else Toast.makeText(context, "Sem permissão de microfone — dá pra liberar nas configurações do app 🎙️", Toast.LENGTH_LONG).show()
    }
    val on = vm.voice.micOn
    val level = vm.voice.micLevel
    Box(
        Modifier.size(size)
            .drawBehind {
                if (on) drawCircle(Festa.hot.copy(alpha = 0.45f), radius = this.size.minDimension / 2 + (level * 8).dp.toPx())
            }
            .clip(CircleShape)
            .background(if (on) Festa.hotGradient else Brush.linearGradient(listOf(Festa.panel2, Festa.panel2)))
            .border(1.dp, if (on) Festa.hot else Festa.borderMid, CircleShape)
            .clickable {
                if (on) vm.toggleMic()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (on) "🎙️" else "🎤", fontSize = (size.value * 0.42f).sp)
    }
}

/** avatar com anel rosa quando a pessoa está com o mic ligado (mais forte quanto mais alto ela fala) */
@Composable
fun SpeakingAvatar(vm: PartyViewModel, m: Member, size: Dp) {
    val talking = vm.voice.speaking[m.clientId] == true
    val level = if (m.clientId == vm.myId) vm.voice.micLevel else vm.voice.levels[m.clientId] ?: 0f
    Box(
        Modifier.size(size)
            .drawBehind {
                if (talking) drawCircle(Festa.hot.copy(alpha = 0.25f + 0.5f * level), radius = this.size.minDimension / 2 + (1.5f + level * 2.5f).dp.toPx())
            }
            .clip(CircleShape).background(Festa.panel3),
        contentAlignment = Alignment.Center,
    ) { Text(avatarFor(m.name), fontSize = (size.value * 0.52f).sp) }
}

/** volume de uma pessoa, só pra mim (igual o controle do lado do nome no site) */
@Composable
fun MemberVolumeDialog(vm: PartyViewModel, m: Member, onDismiss: () -> Unit) {
    val vol = vm.voice.memberVolumes[m.clientId] ?: 1f
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Festa.panel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakingAvatar(vm, m, 34.dp)
                Spacer(Modifier.width(10.dp))
                Text(m.name, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Volume da voz de ${m.name} — só pra você, não muda o que os outros ouvem.", color = Festa.textDim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vol == 0f) "🔇" else if (vol < 0.5f) "🔉" else "🔊", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = vol, onValueChange = { vm.voice.setMemberVolume(m.clientId, it) },
                        colors = SliderDefaults.colors(thumbColor = Festa.amber, activeTrackColor = Festa.amber, inactiveTrackColor = Festa.panel3),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${(vol * 100).toInt()}%", fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.textDim)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Pronto", color = Festa.hot) } },
    )
}

/** 🔊 Volumes: música e vozes separados (igual jogo), abaixar a música quando alguém fala,
 *  e quem está com o mic ligado agora — com um "medidor" pra saber se a voz está chegando. */
@Composable
fun VolumeDialog(vm: PartyViewModel, onDismiss: () -> Unit) {
    val talking = vm.members.filter { it.clientId != vm.myId && vm.voice.speaking[it.clientId] == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Festa.panel,
        title = { Text("🔊 Volume", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                VolumeRow("🎵", "Música", vm.musicVolume / 100f, "${vm.musicVolume}%") { vm.setMusicVolumeLevel((it * 100).toInt()) }
                Spacer(Modifier.height(14.dp))
                // até 200%: reforço pra quando a pessoa fala baixinho ou a rede dela é ruim
                VolumeRow("🗣️", "Vozes", vm.voice.voiceGain / 2f, "${(vm.voice.voiceGain * 100).toInt()}%") { vm.setVoiceVolumeLevel(it * 2f) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Abaixar a música quando alguém fala", color = Festa.textLight, fontSize = 14.sp)
                        Text(if (vm.musicDucked) "abaixada agora 🔉" else "volta sozinha quando param de falar", color = Festa.textFaint, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(
                        checked = vm.duckMusic, onCheckedChange = { vm.setDuckMusicEnabled(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Festa.hot, checkedThumbColor = Festa.onHot),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("COM O MICROFONE LIGADO", style = Festa.label)
                Spacer(Modifier.height(8.dp))
                if (talking.isEmpty()) {
                    Text("Ninguém está com o microfone ligado agora — por isso não tem voz pra ouvir.", color = Festa.textDim, fontSize = 13.sp, lineHeight = 18.sp)
                } else talking.forEach { m ->
                    val lvl = vm.voice.levels[m.clientId] ?: 0f
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpeakingAvatar(vm, m, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = Festa.textLight, fontSize = 13.sp)
                            // barrinha que mexe com a voz dessa pessoa: se ela fala e isso não
                            // mexe, a voz não está chegando no seu celular
                            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Festa.panel3)) {
                                Box(Modifier.fillMaxWidth(lvl.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Festa.hot))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(if (vm.voice.levels.containsKey(m.clientId)) "chegando" else "conectando...", color = Festa.textFaint, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Pronto", color = Festa.hot) } },
    )
}

@Composable
private fun VolumeRow(icon: String, label: String, value: Float, valueText: String, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$icon  $label", color = Festa.textLight, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.textDim)
        }
        Slider(
            value = value, onValueChange = onChange,
            colors = SliderDefaults.colors(thumbColor = Festa.amber, activeTrackColor = Festa.amber, inactiveTrackColor = Festa.panel3),
        )
    }
}
