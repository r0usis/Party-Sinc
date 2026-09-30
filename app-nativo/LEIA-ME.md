# App nativo do Festa Sync (Android)

App de verdade, em Kotlin + Jetpack Compose. **Não** é o site dentro de uma janela: as
telas, a fila, o chat e a sincronização do player são código Android. Ele conversa com o
mesmo servidor do site (`festa-sync.r0usis.partykit.dev`), então quem está no app e quem
está no site ficam **na mesma sala**, vendo e ouvindo a mesma coisa.

A única parte "web" é o vídeo: o YouTube só deixa tocar vídeo pelo player dele, então o
vídeo aparece no player oficial (biblioteca `android-youtube-player`).

## Está sendo feito por partes

| Parte | O quê | Situação |
|---|---|---|
| 1 | Entrar/criar sala, fila, player sincronizado, chat (com fotos), quem tá na festa | ✅ pronto |
| 2 | Chat de voz | a fazer |
| 3 | Jogos, um por vez (2048, forca, roleta, contexto, desenho, Mimic) | a fazer |
| 4 | Segundo plano (tela apagada), controles na tela de bloqueio | a fazer |

Enquanto não estiver completo, o app se chama **"Festa Sync (novo)"** e instala **ao lado**
do app antigo (pasta `android/`), sem substituir. Quando tudo estiver pronto, sai o
sufixo `.nativo` do `applicationId` em `app/build.gradle.kts` e ele passa a atualizar o antigo.

## Instalar

Mesmo jeito do app antigo: manda o `.apk` pro celular, toca nele, permite "instalar apps
desta fonte" e, se o Play Protect reclamar, "Instalar mesmo assim".

## Gerar o APK

Precisa das ferramentas em `~/android-build-tools` (JDK 17 em `jdk/`, SDK em `sdk/`) e de um
`local.properties` com `sdk.dir=/home/<você>/android-build-tools/sdk` (não vai pro git).

```bash
cd app-nativo
JAVA_HOME=~/android-build-tools/jdk ./gradlew assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

Assina com a mesma chave do app antigo (`~/android-build-tools/festa-sync.jks`), lida de
fora do git. Sem ela, assina com a chave de debug (serve pra testar).

## Onde fica cada coisa

- `net/Model.kt`: formato das mensagens do servidor (igual `party/server.js`).
- `net/PartyConnection.kt`: WebSocket com a sala, reconexão, ping/pong.
- `PartyViewModel.kt`: estado da sala e a **sincronização do player**. É a mesma lógica do
  site (`loadVideo`/`driftCorrect`/`handleVideoEnded` em `public/index.html`). Se mudar
  uma, confira a outra, senão app e site passam a tocar diferente.
- `ui/`: telas (entrar, sala com abas Música · Jogos · Chat).
