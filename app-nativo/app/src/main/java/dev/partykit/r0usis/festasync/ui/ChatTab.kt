package dev.partykit.r0usis.festasync.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.ChatMessage
import dev.partykit.r0usis.festasync.net.avatarFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatTab(vm: PartyViewModel, onVolume: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = vm.state?.chatLog.orEmpty()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    // aviso de privacidade: fecha no ✕ e fica fechado (o ⓘ traz de volta)
    val prefs = remember { context.getSharedPreferences("festaSync", Context.MODE_PRIVATE) }
    var privacyHidden by remember { mutableStateOf(prefs.getBoolean("avisoPrivacidadeFechado", false)) }
    fun setPrivacyHidden(v: Boolean) { privacyHidden = v; prefs.edit().putBoolean("avisoPrivacidadeFechado", v).apply() }

    // foto nova do chat: mesma compressão do site (lado maior até 1280px, jpeg ~72%, até 500KB)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val dataUrl = withContext(Dispatchers.Default) { compressForChat(context, uri) }
            if (dataUrl == null) Toast.makeText(context, "Não consegui usar essa imagem 😕", Toast.LENGTH_SHORT).show()
            pendingImage = dataUrl
        }
    }

    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // 6f — quem tá na festa: avatares 46 com nome; você com anel rosa; mic ligado = selo rosa
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            val ordered = vm.members.sortedBy { if (it.clientId == vm.myId) 0 else 1 }
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ordered, key = { it.clientId }) { m ->
                    val me = m.clientId == vm.myId
                    val talking = vm.voice.speaking[m.clientId] == true
                    Column(
                        Modifier.width(58.dp).clip(RoundedCornerShape(12.dp))
                            // tocar em outra pessoa abre a folha de volume (só pra você)
                            .clickable(enabled = !me) { onVolume() }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Box(
                                Modifier.size(46.dp).then(if (me) Modifier.border(2.dp, Festa.hot, CircleShape).padding(3.dp) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) { SpeakingAvatar(vm, m, if (me) 40.dp else 46.dp) }
                            if (talking) Box(
                                Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(Festa.hot)
                                    .border(2.dp, Festa.bgDeep, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { FIcon(FestaIcons.mic, 10.dp, Festa.onHot) }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(m.name, color = Festa.textMid, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.padding(end = 14.dp, top = 4.dp), horizontalAlignment = Alignment.End) {
                Text("${vm.members.size}/${vm.maxPeople}", fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.textDim)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(28.dp).clip(CircleShape).clickable { setPrivacyHidden(!privacyHidden) }, contentAlignment = Alignment.Center) {
                    FIcon(FestaIcons.info, 16.dp, Festa.textFaint)
                }
            }
        }
        if (!privacyHidden) {
            Row(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Festa.panel).border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp))
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FIcon(FestaIcons.lock, 16.dp, Festa.textFaint)
                Spacer(Modifier.width(10.dp))
                Text("Seu mic só liga quando você toca nele. Toque numa pessoa pra ajustar o volume dela.", color = Festa.textDim, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { setPrivacyHidden(true) }, contentAlignment = Alignment.Center) {
                    FIcon(FestaIcons.x, 16.dp, Festa.textFaint)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Festa.borderSoft))

        // mensagens
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (log.isEmpty()) {
                Text("Nenhuma mensagem ainda — manda um oi! 👋", color = Festa.textDim, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    log.forEachIndexed { i, m ->
                        // separador "HOJE" / "ONTEM" / data quando muda o dia
                        val day = dayKey(m.ts)
                        if (i == 0 || dayKey(log[i - 1].ts) != day) item(key = "dia-$day") { DaySeparator(day) }
                        item(key = m.id) { ChatBubble(m, mine = m.clientId == vm.myId, hideImages = vm.workMode) }
                    }
                }
            }
        }

        pendingImage?.let { img ->
            Row(Modifier.fillMaxWidth().background(Festa.panel).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DataUrlImage("pending", img, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Foto pronta pra enviar", color = Festa.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("✕", color = Festa.textDim, fontSize = 16.sp, modifier = Modifier.clickable { pendingImage = null }.padding(8.dp))
            }
        }

        fun send() {
            if (text.isBlank() && pendingImage == null) return
            vm.sendChat(text, pendingImage)
            text = ""; pendingImage = null
        }
        Row(
            Modifier.fillMaxWidth().background(Festa.bgDeep).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Festa.panel).border(1.dp, Festa.borderMid, CircleShape)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) { FIcon(FestaIcons.clip, 20.dp, Festa.textDim) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).height(44.dp).clip(CircleShape).background(Festa.panel).border(1.dp, Festa.borderMid, CircleShape).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text("Escreva uma mensagem...", color = Festa.textGhost, fontSize = 14.sp, maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(
                    value = text, onValueChange = { text = it.take(300) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Festa.textLight, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Festa.hot),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Festa.hotGradient).clickable { send() },
                contentAlignment = Alignment.Center,
            ) { FIcon(FestaIcons.send, 20.dp, Festa.onHot) }
        }
    }
}

private fun dayKey(ts: Long): Long {
    val c = java.util.Calendar.getInstance().apply {
        timeInMillis = if (ts > 0) ts else System.currentTimeMillis()
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

private val dayFormat = SimpleDateFormat("dd 'de' MMM", Locale("pt", "BR"))

@Composable
private fun DaySeparator(day: Long) {
    val today = dayKey(System.currentTimeMillis())
    val diff = Math.round((today - day) / 86_400_000.0)
    val label = when (diff) { 0L -> "HOJE"; 1L -> "ONTEM"; else -> dayFormat.format(Date(day)).uppercase() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Festa.borderSoft))
        Text(label, style = Festa.label.copy(fontSize = 9.5.sp), modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Festa.borderSoft))
    }
}

private val SOLO_LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/** mensagem que é só um link vira cartão: quadradinho com ícone, domínio e caminho */
@Composable
private fun LinkCard(url: String, mine: Boolean) {
    val uri = remember(url) { android.net.Uri.parse(url) }
    val context = LocalContext.current
    Row(
        Modifier.widthIn(max = 290.dp).clickable {
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) } catch (e: Exception) { }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (mine) Festa.onHot.copy(alpha = 0.12f) else Festa.panel3),
            contentAlignment = Alignment.Center,
        ) { FIcon(FestaIcons.link, 18.dp, if (mine) Festa.onHot else Festa.textDim) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text((uri.host ?: url).removePrefix("www."), color = if (mine) Festa.onHot else Festa.textLight, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                ((uri.encodedPath ?: "") + (uri.encodedQuery?.let { "?$it" } ?: "")).ifEmpty { "/" },
                color = if (mine) Festa.onHot.copy(alpha = 0.7f) else Festa.textFaint, fontFamily = Festa.mono, fontSize = 10.5.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

@Composable
private fun ChatBubble(m: ChatMessage, mine: Boolean, hideImages: Boolean) {
    val bubble = if (mine) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    val soloLink = m.image == null && SOLO_LINK.matches(m.text.trim())
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(avatarFor(m.name), fontSize = 12.sp)
            Spacer(Modifier.width(5.dp))
            Text(m.name.uppercase(), style = Festa.label.copy(color = if (mine) Festa.hot else Festa.amber))
            Spacer(Modifier.width(6.dp))
            Text(timeFormat.format(Date(if (m.ts > 0) m.ts else System.currentTimeMillis())), fontFamily = Festa.mono, fontSize = 9.sp, color = Festa.textGhost)
        }
        Spacer(Modifier.height(3.dp))
        Column(
            Modifier.widthIn(max = 290.dp).clip(bubble)
                .then(if (mine) Modifier.background(Festa.hotGradient) else Modifier.background(Festa.panel2))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            if (soloLink) LinkCard(m.text.trim(), mine)
            else if (m.text.isNotEmpty()) Text(m.text, color = if (mine) Festa.onHot else Festa.textLight, fontSize = 14.sp, lineHeight = 19.sp)
            m.image?.let { img ->
                if (m.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                // modo trabalho: a foto só aparece depois de um toque
                var revealed by remember(m.id) { mutableStateOf(false) }
                if (hideImages && !revealed) {
                    Text(
                        "📷 Foto — toque pra ver", color = if (mine) Festa.onHot else Festa.textDim, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Festa.bgDeep.copy(alpha = 0.25f)).clickable { revealed = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                } else {
                    DataUrlImage(m.id, img, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(10.dp)))
                }
            }
        }
    }
}

// fotos do chat chegam como data URL (base64) dentro da própria mensagem — decodifica fora da
// thread principal e guarda num cache pra não decodificar de novo a cada rolagem
private val imageCache = LruCache<String, ImageBitmap>(24)

@Composable
private fun DataUrlImage(key: String, dataUrl: String, modifier: Modifier) {
    val bitmap by produceState(imageCache.get(key), key, dataUrl) {
        if (value == null) value = withContext(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { imageCache.put(key, it) }
            } catch (e: Exception) { null }
        }
    }
    bitmap?.let { Image(it, contentDescription = "foto do chat", contentScale = ContentScale.Fit, modifier = modifier) }
        ?: Box(modifier.height(120.dp).background(Festa.panel3))
}

private fun compressForChat(context: Context, uri: Uri): String? = try {
    val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width; val h = info.size.height
        val scale = 1280f / maxOf(w, h)
        if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }
    var quality = 72
    var out: String
    do {
        val bytes = ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        out = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        quality -= 15
    } while (out.length > 500_000 && quality > 20)
    out.takeIf { it.length <= 500_000 }
} catch (e: Exception) { null }
