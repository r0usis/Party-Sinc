# App Android do Festa Sync

O app é uma "casca" que abre o site (https://festa-sync.r0usis.partykit.dev) em tela cheia.
Tudo que ele faz (salas, música, chat, voz, jogos) vem do site. **Atualizou o site, o app já
está atualizado**, não precisa gerar APK de novo. Só precisa gerar outro APK se mudar algo
desta pasta (ícone, nome, permissões, `MainActivity.java`).

## Instalar no celular

1. Mande o `festa-sync.apk` pro celular (WhatsApp, Drive, cabo USB...).
2. Toque no arquivo. O Android vai pedir pra **permitir instalar apps desta fonte**
   (o app de onde você abriu o arquivo, tipo WhatsApp ou Arquivos). Permita e toque em Instalar.
3. Se o Play Protect avisar "app desconhecido", toque em *Instalar mesmo assim*. Esse aviso
   aparece porque o app não veio da Play Store.

O microfone só é pedido na primeira vez que alguém usa o chat de voz ou grava no Mimic Party.

## O que muda em relação ao navegador

- Links de sala (`https://festa-sync.r0usis.partykit.dev/...`) podem abrir direto no app.
- A tela não apaga sozinha com o app aberto.
- **Segundo plano:** enquanto você está numa sala, a música e o chat de voz continuam
  tocando mesmo com a tela apagada ou em outro app. Fica uma notificação "Na festa: SALA"
  com o botão **Sair da festa**, que fecha o app de verdade. Fechar o app pelos recentes
  (arrastar pro lado) também encerra. Na primeira vez, o Android 13+ pergunta se pode
  mostrar notificações; pode aceitar.
- Alguns celulares (Xiaomi, Samsung, Motorola...) têm "economia de bateria" agressiva que
  fecha apps em segundo plano mesmo assim. Se a música parar depois de um tempo, em
  Configurações → Apps → Festa Sync → Bateria, escolha "Sem restrições".
- Links pra outros sites abrem no navegador do celular.
- **Não tem** compartilhar tela (o Android não deixa em WebView; o botão some sozinho) nem
  janela flutuante (PiP) dos jogos.

## Gerar o APK de novo

Precisa das ferramentas do Android em `~/android-build-tools` (JDK 17, `platforms/android-35`,
`build-tools/35.0.0`). Aí é só rodar:

```bash
./android/build.sh                               # gera android/build/festa-sync.apk
VERSION_CODE=2 VERSION_NAME=1.1 ./android/build.sh   # versão nova, pra instalar por cima
```

A chave de assinatura fica em `~/android-build-tools/festa-sync.jks` (a senha fica em
`festa-sync-keystore-senha.txt`, na mesma pasta), **fora do git**. Guarde uma cópia dela.
Se ela for perdida, uma versão nova do app não instala por cima da antiga: cada pessoa vai
precisar desinstalar a antiga antes.
