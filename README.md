# Festa Sync

Fila do YouTube sincronizada em tempo real pra galera de uma festa: qualquer pessoa
na sala toca, pausa, pula e adiciona música, e todo mundo vê/ouve a mesma coisa,
no mesmo instante.

## 🔗 Link da festa (é esse que você manda pra galera)

**https://festa-sync.r0usis.partykit.dev**

⚠️ **Não é o Vercel, nem o GitHub Pages.** Se esse repositório também tiver GitHub
Pages ativado (Settings → Pages) ou algum projeto solto no Vercel, **eles não
servem** — mostram a telinha, mas sem servidor de verdade por trás, então
ninguém sincroniza nada (sem sala, sem fila, sem sincronia). O link acima é o
único que tem o backend rodando de fato. Motivo técnico na seção "Modo PartyKit"
mais abaixo.

## Como funciona / dois jeitos de rodar

Tem **dois jeitos** de ter essa aplicação no ar, com o **mesmo cliente**
(`public/index.html`) nos dois:

- **PartyKit** (`party/server.js`) — é o do link acima. Roda na nuvem
  (Cloudflare), sempre no ar, com **deploy automático a cada `git push`** —
  não precisa deixar seu PC ligado nem gerenciar túnel. **É o modo em uso.**
- **Self-hosted** (`server.js`) — roda no seu PC, com Express + WebSocket.
  Funciona até sem internet, se a galera estiver na mesma rede Wi-Fi. Você
  que liga e desliga. Alternativa pra quem quer rodar localmente / numa
  rede fechada, sem depender de nuvem nenhuma.

Escolha uma seção abaixo (ou as duas, se quiser as duas opções disponíveis).

## Interface

- **Tela de entrada** — separada da festa em si: escolhe entre a aba "Criar sala"
  (você define nome da sala, senha e o máximo de pessoas) ou "Entrar em sala"
  (código + senha de quem já criou).
- **Tela da festa** — layout de duas colunas: uma sidebar à esquerda com quem
  está na sala (cada pessoa com um avatarzinho de festa 🥳🕺💃🎊 sorteado a partir
  do nome dela) e, em destaque, o vídeo, os controles (play/pause/±10s/anterior/
  próxima) e a fila do aux. No celular, empilha em coluna com o vídeo primeiro.
- A música muda de posição/toca/pausa pra todo mundo ao mesmo tempo — a
  sincronia se autocorrige continuamente, e quando uma música termina a
  próxima da fila entra sozinha.
- Se o navegador de alguém bloquear o som (política de autoplay — comum em
  quem só *recebeu* a música tocando via sync, sem ter clicado em nada), tem um
  botão "🔊 se não tocar sozinho, toque aqui" embaixo do player.

## Modo PartyKit (recomendado — é o que está no ar no link acima)

Aqui a lógica do servidor mora em `party/server.js` (adaptação de `server.js` pro
formato que o [PartyKit](https://www.partykit.io) espera) e cada sala da Festa
Sync vira uma "party" isolada — o próprio PartyKit cuida de criar/gerenciar uma
instância por sala. A configuração fica em `partykit.json`.

### 1. Testar localmente

```bash
npm install
npm run dev:party
```

Sobe em `http://localhost:1999` — mesma interface, mesmo comportamento.

### 2. Deploy manual (pra testar antes de automatizar)

```bash
npm run deploy
```

Na primeira vez, abre o navegador pra você logar com sua conta do GitHub e
autorizar o PartyKit. Depois disso, sua Festa Sync fica no ar em
`https://festa-sync.<seu-usuário-github>.partykit.dev` — permanente, sem
precisar do seu PC ligado.

### 3. Deploy automático a cada `git push`

Isso já vem pronto em `.github/workflows/deploy.yml` — só falta autorizar o
GitHub Actions a fazer o deploy por você:

1. Gera um token: `npx partykit token generate` (isso te dá dois valores:
   `PARTYKIT_LOGIN` e `PARTYKIT_TOKEN`).
2. No GitHub, vai em **Settings → Secrets and variables → Actions** do
   repositório e cria dois *repository secrets* com esses nomes e valores.
3. Pronto — a partir do próximo `git push` na branch `main`, o GitHub Actions
   roda `npx partykit deploy` sozinho. Acompanha em **Actions** no GitHub.

Se um run ficar "em andamento" pra sempre sem nunca terminar, é só um runner do
GitHub que travou (acontece raramente) — cancela ele manualmente na aba Actions.
Isso não impede os próximos pushes de rodar e completar normalmente.

## Modo self-hosted (alternativa, no seu PC)

### 1. Pré-requisito

Precisa ter Node.js 18+ instalado. Confirma com:

```bash
node -v
```

Se não tiver, baixa a versão LTS em https://nodejs.org.

### 2. Instalar e rodar

Dentro desta pasta:

```bash
npm install
npm start
```

Isso sobe o servidor em `http://localhost:3000`. Abre esse link no seu navegador
pra testar sozinha primeiro.

### 3. Deixar acessível pra galera

#### Opção 0 — mesma rede Wi-Fi (mais simples, zero ferramentas extra)

Se a galera vai estar na sua casa/mesma rede, não precisa de túnel nenhum.

1. Descubra seu IP local:
   - Linux/Mac: `hostname -I` (ou `ifconfig`)
   - Windows: `ipconfig` (procure "Endereço IPv4")
2. Manda pra galera: `http://SEU_IP_LOCAL:3000` (ex: `http://192.168.0.15:3000`)
3. Se ninguém conseguir abrir, o firewall do seu PC pode estar bloqueando a porta
   3000 pra conexões de entrada — libera essa porta pra rede local.

#### Opção A — ngrok (acesso pela internet, redes diferentes)

1. Cria conta grátis em https://ngrok.com e pega seu authtoken.
2. Configura uma vez: `npx ngrok config add-authtoken SEU_TOKEN`
3. Com o servidor rodando, em outro terminal: `npx ngrok http 3000`
4. Ele te dá um link tipo `https://alguma-coisa.ngrok-free.app` — manda esse pra galera.

⚠️ No plano grátis o link muda toda vez que você reinicia o ngrok.

#### Opção B — Cloudflare Tunnel (sem precisar de conta)

1. Instala o `cloudflared`:
   https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
2. Com o servidor rodando: `cloudflared tunnel --url http://localhost:3000`
3. Ele te dá um link `https://alguma-coisa.trycloudflare.com` — manda pra galera.

Tanto ngrok quanto Cloudflare Tunnel funcionam fazendo uma conexão de *saída* do
seu PC — não precisa mexer em configuração de roteador nem abrir porta pra fora.

### 4. Compartilhar com a galera

Uma pessoa **cria a sala** primeiro (aba "Criar sala"): escolhe o nome da sala, uma
senha e o máximo de pessoas que podem entrar. Depois manda pra galera o link
(local, ngrok ou cloudflare) + a senha por fora (WhatsApp, por exemplo — a senha
não vai no link). O botão de copiar (⧉) dentro do app gera o link com
`?room=CODIGO` preenchido, então quem clicar já cai na aba "Entrar em sala" com o
código certo — só falta escrever o nome e digitar a senha.

Se alguém tentar criar uma sala com um nome que já existe, entrar com senha errada
ou entrar numa sala que já bateu o limite de gente, o app avisa na hora — não é
preciso reinventar código pra isso, o servidor já recusa a conexão nesses casos.

### 5. Encerrar a festa

`Ctrl+C` no terminal do `node server.js`, e no terminal do túnel (ngrok/cloudflared),
se estiver usando.

## Self-hosted x PartyKit — o que muda

- O cliente (`public/index.html`) é o **mesmo** nos dois modos — ele conecta em
  `/parties/main/<sala>`, que tanto o `server.js` quanto o PartyKit entendem.
- Não rode os dois ao mesmo tempo esperando que compartilhem sala: são dois
  backends com estado separado — uma sala criada no self-hosted não existe
  pro PartyKit, e vice-versa.
- No PartyKit, uma sala parada por alguns segundos sem ninguém conectado pode
  "hibernar" (o PartyKit economiza recursos assim). Guardamos os dados da sala
  (senha, limite de gente, fila) em armazenamento próprio do PartyKit, então
  ela acorda sozinha do jeito que estava quando alguém volta a conectar — não é
  igual ao self-hosted (que só zera se você reiniciar o processo manualmente),
  mas o efeito pra quem tá usando é parecido: a sala não desaparece.

## Observações importantes

- **Sem persistência de verdade**: no self-hosted, o estado (sala, fila, senha,
  música tocando) fica só na memória do processo — reiniciar o servidor zera
  tudo, e uma sala vazia por 10 minutos é apagada sozinha. No PartyKit, o
  estado é salvo pra sobreviver a hibernação, mas ainda é "por sala" e sem
  garantia de durar pra sempre — nenhum dos dois modos é feito pra guardar
  histórico de festas passadas.
- **Senha simples, não criptografia**: a senha da sala é guardada em texto puro
  (na memória do processo, ou no armazenamento do PartyKit) e comparada
  direto — dá pra impedir estranho de entrar, mas não é grau bancário. Ótimo
  pra galera de confiança; não é pra postar publicamente. Quem já está dentro
  pode tocar/pausar/pular/adicionar músicas normalmente — a senha só controla
  quem entra.
- Vídeos com **incorporação desativada** pelo dono continuam não tocando —
  isso é restrição do YouTube, sem contorno possível.
- **Avanço automático**: quando a música termina, a próxima da fila toca sozinha
  (detectado via evento `ENDED` da API do YouTube). No fim da fila, o player só para.
- Peça pra galera usar os botões do app (não clicar direto no vídeo) — clique
  direto no player não é detectado pelo servidor e desincroniza a pessoa.
