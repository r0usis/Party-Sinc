package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.partykit.r0usis.festasync.PartyViewModel
import dev.partykit.r0usis.festasync.net.sanitizeRoomCode

@Composable
fun Logo(size: Int) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Festa.textLight)) { append("FESTA") }
            withStyle(SpanStyle(color = Festa.hot)) { append("SYNC") }
        },
        fontFamily = Festa.display, fontSize = size.sp, letterSpacing = 0.5.sp,
    )
}

@Composable
fun JoinScreen(vm: PartyViewModel, initialRoom: String?) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(vm.savedName) }
    var code by rememberSaveable { mutableStateOf(initialRoom ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var maxPeople by rememberSaveable { mutableStateOf("10") }

    fun submit() {
        if (vm.joining) return
        val room = sanitizeRoomCode(code)
        if (creating) vm.create(name, room, password, maxPeople.toIntOrNull() ?: 10)
        else vm.join(name, room, password)
    }

    val context = LocalContext.current
    val fromLink = initialRoom != null && sanitizeRoomCode(code) == sanitizeRoomCode(initialRoom)

    // 6a — marca em cima, formulário embaixo (perto do polegar)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Festa.bgDeep)
            .background(Brush.radialGradient(listOf(Festa.hot.copy(alpha = 0.16f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(0f, 0f)))
            .safeDrawingPadding().imePadding(),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(top = 32.dp)) {
                Text("FILA DO AUX · WATCH PARTY", style = Festa.label.copy(color = Festa.amber))
                Spacer(Modifier.height(8.dp))
                Logo(68)
                Text(
                    "Toquem vídeos do YouTube em sincronia — mesma música, mesmo segundo, pra todo mundo.",
                    color = Festa.textDim, fontSize = 15.sp, lineHeight = 22.sp,
                )
            }
            Column(Modifier.padding(top = 28.dp)) {
                // abas Entrar / Criar
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Festa.bgDeep)
                        .border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp)).padding(4.dp),
                ) {
                    listOf(false to "Entrar em sala", true to "Criar sala").forEach { (isCreate, label) ->
                        val on = creating == isCreate
                        Box(
                            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) Festa.panel3 else Festa.bgDeep)
                                .then(if (on) Modifier.border(1.dp, Festa.hot.copy(alpha = 0.35f), RoundedCornerShape(10.dp)) else Modifier)
                                .clickable { creating = isCreate; vm.clearJoinError() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (on) Festa.textLight else Festa.textFaint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))

                FieldLabel("SEU NOME")
                FestaInput(
                    name, { name = it.take(24) }, "Como te chamam na festa?",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(if (creating) "NOME DA SALA" else "CÓDIGO DA SALA", Festa.amber)
                    Spacer(Modifier.weight(1f))
                    if (!creating && fromLink) Text(
                        "VEIO DO LINK", style = Festa.label.copy(color = Festa.amber, fontSize = 9.sp),
                        modifier = Modifier.padding(bottom = 6.dp).clip(CircleShape).border(1.dp, Festa.amber.copy(alpha = 0.45f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                FestaInput(
                    // NÃO dá pra limpar/colocar em maiúscula aqui dentro enquanto a pessoa digita:
                    // mexer no texto no meio da digitação bagunça o teclado (APPTESTE7 virava
                    // "171"). Só MOSTRA em maiúscula; a limpeza de verdade é no submit().
                    code, { code = it.take(24) }, if (creating) "Ex: ANIVERSARIODABIA" else "CÓDIGO",
                    height = 54.dp, highlight = !creating && fromLink,
                    visualTransformation = UppercaseTransformation,
                    textStyle = TextStyle(fontFamily = Festa.mono, fontSize = 18.sp, letterSpacing = 3.sp, color = Festa.textLight),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    trailing = if (creating) null else ({
                        // colar: aceita o código puro ou o link inteiro da sala
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Festa.panel2).border(1.dp, Festa.borderMid, RoundedCornerShape(10.dp))
                                .clickable {
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    val txt = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                    val fromUrl = try { android.net.Uri.parse(txt.trim()).getQueryParameter("room") } catch (e: Exception) { null }
                                    val pasted = sanitizeRoomCode(fromUrl ?: txt)
                                    if (pasted.isEmpty()) android.widget.Toast.makeText(context, "Não achei um código pra colar", android.widget.Toast.LENGTH_SHORT).show()
                                    else code = pasted
                                },
                            contentAlignment = Alignment.Center,
                        ) { FIcon(FestaIcons.clipboard, 18.dp, Festa.textDim) }
                    }),
                )
                Spacer(Modifier.height(14.dp))

                Row {
                    FieldLabel("SENHA DA SALA", Festa.amber)
                    Spacer(Modifier.weight(1f))
                    if (!creating) FieldLabel("COMBINADA POR FORA", Festa.amber)
                }
                FestaInput(
                    password, { password = it.take(64) }, if (creating) "Só quem souber a senha entra" else "Peça pra quem criou a sala",
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailing = {
                        Text(if (showPassword) "🙈" else "👁", fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable { showPassword = !showPassword }.padding(6.dp))
                    },
                )

                if (creating) {
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("MÁXIMO DE PESSOAS")
                    FestaInput(
                        maxPeople, { maxPeople = it.filter(Char::isDigit).take(2) }, "10",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                vm.joinError?.let { err ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        err, color = Festa.danger, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Festa.danger.copy(alpha = 0.08f))
                            .border(1.dp, Festa.danger.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(14.dp),
                    )
                }

                Spacer(Modifier.height(18.dp))
                if (vm.joining) {
                    Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Festa.hot, strokeWidth = 3.dp)
                    }
                } else {
                    HotButton(if (creating) "Criar sala →" else "Entrar na sala →", Modifier.fillMaxWidth().height(54.dp), fill = true) { submit() }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(if (creating) "Já tem um código? " else "Ninguém te passou um código? ", color = Festa.textDim, fontSize = 14.sp)
                    Text(
                        if (creating) "Entrar numa sala" else "Criar a sua sala",
                        color = Festa.amber, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.clickable { creating = !creating; vm.clearJoinError() },
                    )
                }
            }
        }
    }
}

/** campo do design (48dp; o do código, 54): fundo escuro, borda fininha, rosa com foco */
@Composable
private fun FestaInput(
    value: String, onChange: (String) -> Unit, placeholder: String,
    height: androidx.compose.ui.unit.Dp = 48.dp, highlight: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = TextStyle(fontSize = 15.sp, color = Festa.textLight),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = textStyle, cursorBrush = SolidColor(Festa.hot),
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(Color(0xFF07040A))
                    .border(1.dp, if (focused || highlight) Festa.hot.copy(alpha = if (focused) 1f else 0.6f) else Festa.borderSoft, RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Festa.textGhost, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

@Composable
private fun FieldLabel(text: String, color: Color = Festa.textFaint) {
    Text(text, style = Festa.label.copy(color = color), modifier = Modifier.padding(bottom = 6.dp, start = 2.dp), textAlign = TextAlign.Start)
}

// mesmo tamanho de texto, só em maiúscula — então a posição do cursor não muda
private object UppercaseTransformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString) =
        androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(text.text.uppercase()),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
}
