package dev.partykit.r0usis.festasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
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

    Box(
        Modifier.fillMaxSize().background(Festa.bgDeep).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text("FILA DO AUX · WATCH PARTY", style = Festa.label.copy(color = Festa.amber))
            Spacer(Modifier.height(8.dp))
            Logo(64)
            Text(
                "Toquem vídeos do YouTube em sincronia — mesma música, mesmo segundo, pra todo mundo.",
                color = Festa.textDim, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(24.dp))

            // abas Entrar / Criar
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Festa.bgDeep)
                    .border(1.dp, Festa.borderSoft, RoundedCornerShape(14.dp)).padding(4.dp),
            ) {
                listOf(false to "Entrar em sala", true to "Criar sala").forEach { (isCreate, label) ->
                    val on = creating == isCreate
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Festa.panel3 else Festa.bgDeep)
                            .clickable { creating = isCreate; vm.clearJoinError() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (on) Festa.textLight else Festa.textFaint, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            FieldLabel("SEU NOME")
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24) },
                placeholder = { Text("Como te chamam na festa?") },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = festaFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))

            FieldLabel(if (creating) "NOME DA SALA" else "CÓDIGO DA SALA")
            OutlinedTextField(
                // NÃO dá pra limpar/colocar em maiúscula aqui dentro enquanto a pessoa digita:
                // mexer no texto no meio da digitação bagunça o teclado (APPTESTE7 virava
                // "171"). Só MOSTRA em maiúscula; a limpeza de verdade é no submit().
                value = code, onValueChange = { code = it.take(24) },
                visualTransformation = UppercaseTransformation,
                placeholder = { Text(if (creating) "Ex: ANIVERSARIODABIA" else "CÓDIGO") },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = festaFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Festa.mono, fontSize = 18.sp, letterSpacing = 3.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))

            FieldLabel("SENHA DA SALA")
            OutlinedTextField(
                value = password, onValueChange = { password = it.take(64) },
                placeholder = { Text(if (creating) "Só quem souber a senha entra" else "Peça pra quem criou a sala") },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = festaFieldColors(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(if (showPassword) "🙈" else "👁", fontSize = 18.sp, modifier = Modifier.clickable { showPassword = !showPassword }.padding(10.dp))
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (creating) {
                Spacer(Modifier.height(14.dp))
                FieldLabel("MÁXIMO DE PESSOAS")
                OutlinedTextField(
                    value = maxPeople, onValueChange = { maxPeople = it.filter(Char::isDigit).take(2) },
                    singleLine = true, shape = RoundedCornerShape(12.dp), colors = festaFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
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
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Festa.hot, strokeWidth = 3.dp)
                }
            } else {
                HotButton(if (creating) "Criar sala →" else "Entrar na sala →", Modifier.fillMaxWidth(), fill = true) { submit() }
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

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = Festa.label, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp), textAlign = TextAlign.Start)
}

// mesmo tamanho de texto, só em maiúscula — então a posição do cursor não muda
private object UppercaseTransformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString) =
        androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(text.text.uppercase()),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
}
