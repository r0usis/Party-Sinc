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
fun ChatTab(vm: PartyViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = vm.state?.chatLog.orEmpty()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<String?>(null) }

    // foto nova do chat: mesma compressão do site (lado maior até 1280px, jpeg ~72%, até 500KB)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val dataUrl = withContext(Dispatchers.Default) { compressForChat(context, uri) }
            if (dataUrl == null) Toast.makeText(context, "Não consegui usar essa imagem 😕", Toast.LENGTH_SHORT).show()
            pendingImage = dataUrl
        }
    }

    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // quem tá na festa
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text("QUEM TÁ NA FESTA", style = Festa.label)
            Spacer(Modifier.weight(1f))
            Text("${vm.members.size}/${vm.maxPeople}", fontFamily = Festa.mono, fontSize = 12.sp, color = Festa.textDim)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.members, key = { it.clientId }) { m ->
                val me = m.clientId == vm.myId
                Row(
                    Modifier.clip(CircleShape).background(if (me) Festa.hot.copy(alpha = 0.08f) else Festa.panel)
                        .border(1.dp, if (me) Festa.hot.copy(alpha = 0.3f) else Festa.borderSoft, CircleShape)
                        .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Festa.panel3), contentAlignment = Alignment.Center) { Text(avatarFor(m.name), fontSize = 14.sp) }
                    Spacer(Modifier.width(7.dp))
                    Text(m.name + if (me) " (você)" else "", color = Festa.textMid, fontSize = 13.sp)
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
                    items(log, key = { it.id }) { m -> ChatBubble(m, mine = m.clientId == vm.myId) }
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
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Festa.panel).border(1.dp, Festa.borderMid, RoundedCornerShape(12.dp))
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) { Text("📎", fontSize = 18.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(300) },
                placeholder = { Text("Escreva uma mensagem...") },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = festaFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Festa.hotGradient).clickable { send() },
                contentAlignment = Alignment.Center,
            ) { Text("➤", color = Festa.onHot, fontSize = 18.sp) }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

@Composable
private fun ChatBubble(m: ChatMessage, mine: Boolean) {
    val bubble = if (mine) RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp)
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
            if (m.text.isNotEmpty()) Text(m.text, color = if (mine) Festa.onHot else Festa.textLight, fontSize = 15.sp, lineHeight = 20.sp)
            m.image?.let { img ->
                if (m.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                DataUrlImage(m.id, img, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(10.dp)))
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
