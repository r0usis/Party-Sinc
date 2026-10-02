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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
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
                // ligado: anel fixo de 4dp (rgba(255,61,129,.2)) + um extra que cresce com a voz
                if (on) {
                    drawCircle(Festa.hot.copy(alpha = 0.2f), radius = this.size.minDimension / 2 + 4.dp.toPx())
                    if (level > 0.02f) drawCircle(Festa.hot.copy(alpha = 0.3f), radius = this.size.minDimension / 2 + (4 + level * 6).dp.toPx())
                }
            }
            .clip(CircleShape)
            .background(if (on) Festa.hotGradient else Brush.linearGradient(listOf(Festa.panel2, Festa.panel2)))
            .border(1.dp, if (on) Color.Transparent else Festa.borderMid, CircleShape)
            .clickable {
                if (on) vm.toggleMic()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
        contentAlignment = Alignment.Center,
    ) {
        FIcon(if (on) FestaIcons.mic else FestaIcons.micoff, (size.value * 0.5f).dp, if (on) Festa.onHot else Festa.textLight)
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

/** 6d — folha de volume: música e vozes separadas (vozes até 200%), abaixar a música quando
 *  alguém fala, e o volume de cada pessoa com o mic ligado. Tudo só neste aparelho. */
@Composable
fun VolumeSheet(vm: PartyViewModel, onDismiss: () -> Unit) {
    val talking = vm.members.filter { it.clientId != vm.myId && vm.voice.speaking[it.clientId] == true }
    FestaSheet(onDismiss) {
        SheetTitle("Volume", "SÓ NO SEU APARELHO")
        SheetVolumeRow(FestaIcons.music, "Música", "${vm.musicVolume}%") {
            FestaSlider(vm.musicVolume / 100f, { vm.setMusicVolumeLevel((it * 100).toInt()) })
        }
        Spacer(Modifier.height(10.dp))
        SheetVolumeRow(FestaIcons.mic, "Vozes", "${(vm.voice.voiceGain * 100).toInt()}%") {
            // 0–200%, com marquinha no 100% (o "normal")
            FestaSlider(vm.voice.voiceGain / 2f, { vm.setVoiceVolumeLevel(((it * 40).toInt() / 20f)) }, tick = 0.5f)
            Row(Modifier.fillMaxWidth()) {
                Text("MUDO", style = Festa.label.copy(fontSize = 9.sp))
                Spacer(Modifier.weight(1f))
                Text("NORMAL", style = Festa.label.copy(fontSize = 9.sp))
                Spacer(Modifier.weight(1f))
                Text("2×", style = Festa.label.copy(fontSize = 9.sp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Festa.panel2)
                .border(1.dp, Festa.borderSoft, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Abaixar a música quando alguém fala", color = Festa.textLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (vm.musicDucked) "abaixada agora" else "volta sozinha quando param de falar", color = Festa.textDim, fontSize = 12.5.sp)
            }
            Spacer(Modifier.width(12.dp))
            FestaSwitch(vm.duckMusic) { vm.setDuckMusicEnabled(it) }
        }
        Spacer(Modifier.height(18.dp))
        Text("PESSOAS COM MIC LIGADO", style = Festa.label)
        Spacer(Modifier.height(10.dp))
        if (talking.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().dashedBorder(Festa.borderStrong, 14.dp).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FIcon(FestaIcons.micoff, 20.dp, Festa.textFaint)
                Spacer(Modifier.width(12.dp))
                Text("Ninguém com o mic ligado agora. Quando alguém ligar, o volume de cada um aparece aqui.", color = Festa.textDim, fontSize = 13.sp, lineHeight = 18.sp)
            }
        } else talking.forEach { m ->
            val vol = vm.voice.memberVolumes[m.clientId] ?: 1f
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeakingAvatar(vm, m, 34.dp)
                Spacer(Modifier.width(12.dp))
                Text(m.name, color = Festa.textLight, fontSize = 13.5.sp, maxLines = 1, modifier = Modifier.width(84.dp))
                FestaSlider(vol, { vm.voice.setMemberVolume(m.clientId, it) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        SheetDoneButton(onClick = onDismiss)
    }
}

@Composable
private fun SheetVolumeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FIcon(icon, 20.dp, Festa.textDim)
            Spacer(Modifier.width(10.dp))
            Text(label, color = Festa.textLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.amber)
        }
        slider()
    }
}

/** borda tracejada (o "vazio" do design) */
fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
    )
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()), style = stroke)
}
