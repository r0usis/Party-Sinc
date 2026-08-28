# Festa Sync 🎉

Fila de música do YouTube sincronizada em tempo real: qualquer pessoa na sala
toca, pausa, pula, adiciona música, fala no chat de voz — e todo mundo vê e
ouve a mesma coisa, na mesma hora.

## 🔗 Link da festa

**https://festa-sync.r0usis.partykit.dev**

Abre esse link, cria ou entra numa sala e chama a galera.

## Como usar

1. **Criar uma sala** — na aba "Criar sala": escolhe um nome, uma senha e
   quantas pessoas podem entrar. Manda o link + a senha pra galera por fora
   (WhatsApp, por exemplo — a senha não vai junto no link).
2. **Entrar numa sala** — na aba "Entrar em sala": quem recebeu o link só
   precisa digitar o nome e a senha.
3. **Adicionar música** — cola o link de qualquer vídeo do YouTube na caixinha
   "Cole o link do YouTube aqui..." e clica em Adicionar.
4. **Controlar a festa** — qualquer pessoa pode tocar, pausar, pular (⏮/⏭),
   voltar/avançar 10 segundos, reordenar ou remover músicas da fila. Não tem
   um "dono" fixo do controle — a última ação manda.
5. **Falar com a galera** — clica no ícone de microfone do lado do seu nome
   (na lista de quem está na sala) pra ativar seu áudio. Cada pessoa também
   pode ajustar, só pra si, o volume de cada uma das outras.
6. **Cantar junto** — quando a música tiver letra sincronizada disponível,
   ela aparece sozinha acima dos controles, acompanhando o vídeo.

## Funcionalidades

- **Fila sincronizada de verdade** — mesma música, mesma posição, pra todo
  mundo, o tempo todo, com correção automática se alguém desincronizar.
- **Avanço automático** — quando a música acaba, a próxima da fila entra
  sozinha.
- **Sala com senha e limite de gente** — só entra quem tiver o código e a
  senha certos, e dá pra limitar quantas pessoas cabem na sala.
- **Chat de voz** — converse com a galera direto pelo navegador, sem
  instalar nada. O microfone começa desligado e só liga quando você mesma
  aperta o botão; não existe jeito de ligar o microfone de outra pessoa.
- **Compartilhar tela** — uma pessoa por vez pode mostrar a própria tela pra
  todo mundo (substitui o vídeo do YouTube enquanto durar, sem pausar a
  música). Ninguém consegue parar o compartilhamento de outra pessoa.
- **Karaokê automático** — letra sincronizada aparece sozinha quando
  disponível pra aquela música, e simplesmente não aparece quando não tem.
- **Funciona em celular e computador** — a tela se ajusta ao tamanho.

## Como funciona por baixo dos panos

(Curiosidade — não precisa entender nada disso pra usar o app.)

- O vídeo toca através da **API oficial do YouTube**, não é só um link
  incorporado — é isso que permite sincronizar posição, saber quando a
  música termina e avançar sozinho.
- Um servidorzinho de tempo real, rodando no **PartyKit** (hospedado na
  Cloudflare), mantém todo mundo na mesma página via WebSocket.
- O **chat de voz** usa WebRTC: o áudio viaja direto entre os navegadores
  das pessoas, sem passar pelo servidor.
- A **letra sincronizada** vem do **LRCLIB**, um banco de dados público e
  gratuito de letras com tempo marcado.

## Quer rodar sua própria versão?

Dá pra hospedar essa mesma aplicação de dois jeitos: na nuvem, via PartyKit
(`npm run dev:party` pra testar local, `npm run deploy` pra subir — inclusive
com deploy automático a cada `git push` já configurado), ou no seu próprio
computador (`npm start`, usando o `server.js`). Os dois arquivos de servidor
(`party/server.js` e `server.js`) e o `partykit.json` no repositório trazem
tudo que é preciso pra configurar cada opção.

## Observações importantes

- **Sem persistência de verdade** — o estado de cada sala (fila, senha, quem
  está tocando) é feito pra durar a festa, não pra virar histórico permanente.
- **Senha simples, não criptografia** — dá pra impedir estranho de entrar,
  mas não é grau bancário. Ótimo pra galera de confiança; não é pra postar
  publicamente.
- Vídeos com **incorporação desativada** pelo dono continuam não tocando —
  isso é restrição do YouTube, sem contorno possível.
- Use os controles do app (não clique direto no vídeo) pra manter todo mundo
  sincronizado.
