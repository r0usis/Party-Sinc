// party/server.js — Festa Sync rodando no PartyKit (nuvem, deploy automático a cada push),
// em vez do server.js original (que roda no seu PC / precisa de túnel pra galera acessar).
//
// A mesma regra de negócio do server.js (criar/entrar em sala com senha e limite de gente,
// fila sincronizada) só que adaptada pro modelo do PartyKit: aqui, cada sala da Festa Sync
// já é, por conta própria, uma "Party" isolada (uma instância criada automaticamente pelo
// PartyKit pra cada código de sala) — então não precisamos mais de um Map global de salas
// como no server.js original, é uma instância por sala.
//
// Igual ao server.js: sem "banco de dados" de verdade. O que persistimos em
// `room.storage` é só pra sobreviver caso o PartyKit hiberne a sala por inatividade
// (pode acontecer poucos segundos depois de todo mundo desconectar) — sem isso, um
// respiro de conexão no meio da festa apagaria a sala sozinha, o que seria pior do
// que o comportamento original (lá, só reiniciar o processo manualmente zerava tudo).

const CLOSE_ROOM_EXISTS = 4001;
const CLOSE_ROOM_MISSING = 4002;
const CLOSE_WRONG_PASSWORD = 4003;
const CLOSE_ROOM_FULL = 4004;

function defaultPlaybackState() {
  return {
    queue: [], currentIndex: -1, isPlaying: false, position: 0, updatedAt: Date.now(), hostName: null,
    screenSharerId: null, screenSharerName: null, // quem está compartilhando a tela agora (só uma pessoa por vez)
    drawGame: defaultDrawGameState(),
    hangmanGame: defaultHangmanState(),
    stopGame: defaultStopGameState(),
    chatLog: [], // mensagens de texto da sala — guarda um histórico curto pra quem entra depois também ver
    playlists: [], // listas de música salvas da sala — sobrevivem pra quem entrar depois (persistem de verdade aqui)
    activePlaylistId: null, // qual playlist tá "aberta pra edição" agora — sobrevive a mexer na fila
  };
}
const MAX_PLAYLISTS = 12; // trava de bom senso, não deixa a sala acumular playlist sem fim

// ---------------- jogo de desenho (mini Pictionary) ----------------
// Lista de palavras própria, composta na hora — substantivos simples e comuns do dia a dia,
// fáceis de desenhar. Não é o banco de palavras de nenhum jogo existente.
const DRAW_WORD_BANK = [
  'gato', 'cachorro', 'elefante', 'girafa', 'passarinho', 'peixe', 'coelho', 'borboleta',
  'aranha', 'abelha', 'vaca', 'cavalo', 'porco', 'galinha', 'pato', 'tartaruga', 'cobra',
  'leão', 'macaco', 'urso', 'pinguim', 'polvo', 'caranguejo', 'tubarão', 'baleia',
  'casa', 'árvore', 'sol', 'lua', 'estrela', 'nuvem', 'chuva', 'guarda-chuva', 'praia',
  'montanha', 'rio', 'foguete', 'avião', 'carro', 'bicicleta', 'barco', 'trem', 'ônibus',
  'semáforo', 'ponte', 'castelo', 'igreja', 'escola', 'hospital', 'fazenda',
  'bola', 'boneca', 'pipa', 'violão', 'piano', 'tambor', 'livro', 'lápis', 'tesoura',
  'chave', 'relógio', 'óculos', 'chapéu', 'sapato', 'camiseta', 'mochila', 'guitarra',
  'bolo', 'pizza', 'sorvete', 'maçã', 'banana', 'melancia', 'cenoura', 'pão', 'ovo',
  'café', 'hambúrguer', 'pipoca', 'sanduíche', 'cachorro-quente',
  'coração', 'mão', 'pé', 'olho', 'nariz', 'boca', 'orelha', 'dente',
  'robô', 'fantasma', 'bruxa', 'dinossauro', 'dragão', 'sereia', 'unicórnio',
  'presente', 'bolha', 'vela', 'chave-de-fenda', 'martelo', 'escada', 'cadeira', 'mesa',
  'televisão', 'computador', 'celular', 'câmera', 'lâmpada', 'bandeira', 'coroa',
];
function shuffleArray(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
function pickThreeWords() {
  return shuffleArray(DRAW_WORD_BANK).slice(0, 3);
}
function defaultDrawGameState() {
  return {
    phase: 'idle', // idle | inviting | choosing | drawing | finished
    hostId: null,
    invitedIds: [],
    acceptedIds: [],
    order: [],
    round: 0,
    turnIndex: 0,
    currentDrawerId: null,
    currentDrawerName: null,
    wordLength: 0,
    turnStartedAt: null,
    scores: {},
    names: {},
    lastGuess: null,
  };
}

// ---------------- forca (jogo de adivinhar palavra em grupo) ----------------
const HANGMAN_MAX_WRONG = 6; // tentativas erradas antes de "morrer" (bate com os estágios do desenho no cliente)
// Marcas de acento (faixa Unicode 0300-036F, "combining diacritical marks") depois de
// separar a letra do acento via NFD — construído por código de propósito, pra não deixar
// caractere combinável invisível solto direto no arquivo-fonte.
const DIACRITICS_RE = new RegExp(`[̀-ͯ]`, 'g');
function normalizeLetter(ch) {
  return String(ch || '').normalize('NFD').replace(DIACRITICS_RE, '').toLowerCase();
}
function defaultHangmanState() {
  return {
    phase: 'idle', // idle | inviting | setting | playing | roundEnd | finished
    hostId: null,
    invitedIds: [],
    acceptedIds: [],
    order: [],
    round: 0,
    turnIndex: 0,
    currentSetterId: null,
    currentSetterName: null,
    wordLength: 0,
    guessedLetters: [],
    wrongLetters: [],
    revealedPattern: [], // uma posição por letra da palavra: a letra de verdade se já foi acertada, null se ainda não
    turnStartedAt: null,
    scores: {},
    names: {},
    lastRoundResult: null,
  };
}

// ---------------- roleta de categorias (tipo "Stop"/Adedanha) ----------------
function defaultStopGameState() {
  return {
    theme: '',
    usedLetters: [],
    currentLetter: null,
    roundStartedAt: null,
  };
}

function clampMaxPeople(raw) {
  const n = parseInt(raw, 10);
  if (!Number.isFinite(n)) return 10;
  return Math.min(50, Math.max(2, n));
}

function genId() {
  return Math.random().toString(36).slice(2, 10);
}

function computeEstimatedPosition(state) {
  if (state.currentIndex < 0) return 0;
  if (!state.isPlaying) return state.position;
  return state.position + (Date.now() - state.updatedAt) / 1000;
}

export default class FestaSyncParty {
  constructor(room) {
    this.room = room; // Party.Room — uma instância por sala (o "room.id" é o próprio código da sala)
    this.created = false;
    this.password = '';
    this.maxPeople = 10;
    this.playback = defaultPlaybackState();
    this.pendingWordChoices = null; // as 3 palavras oferecidas ao desenhista atual — só o servidor sabe
    this.turnTimer = null; // cronômetro dos 60s da rodada, pra avançar sozinho se ninguém acertar
    this.hangmanSecretWord = null; // a palavra da forca da rodada atual — só o servidor sabe
    this.hangmanTimer = null; // cronômetro da rodada (tempo esgotado = ninguém acertou)
    this.hangmanRoundEndTimer = null; // folga pra mostrar o resultado antes de trocar de rodada
    // connection.id -> true se mandei um "ping" de app e ainda não voltou o "pong". Aqui (ao
    // contrário do server.js self-hosted) não tem ping/pong de verdade do protocolo do
    // WebSocket disponível — o Cloudflare Workers só expõe send/close pra cima, então o
    // "batimento cardíaco" tem que ser feito na mão, por mensagem mesmo (ver checkHeartbeat).
    this.pendingPings = new Map();
  }

  // Acha (e derruba) conexão morta que nunca chegou a fechar direito — sem isso, ela fica
  // ocupando vaga na sala PRA SEMPRE, até lotar de gente "fantasma" e ninguém mais conseguir
  // entrar (foi exatamente o que aconteceu: sala parada um tempo, alguém caiu sem avisar, e
  // a vaga dela nunca foi liberada). Roda a cada ~20s via alarme, só enquanto tiver gente
  // conectada — sala vazia hiberna em paz, sem ficar acordando o Durable Object à toa.
  async checkHeartbeat() {
    const connections = [...this.room.getConnections()];
    for (const c of connections) {
      if (this.pendingPings.get(c.id)) {
        // não respondeu ao ping do ciclo anterior até agora -> morta de vez
        try { c.close(); } catch (e) { /* já pode ter caído sozinha */ }
        this.pendingPings.delete(c.id);
      } else {
        this.pendingPings.set(c.id, true);
        try { c.send(JSON.stringify({ type: 'ping' })); } catch (e) { this.pendingPings.delete(c.id); }
      }
    }
    if (connections.length) await this.room.storage.setAlarm(Date.now() + 20000);
  }

  async onAlarm() {
    await this.checkHeartbeat();
  }

  // Manda ping pras conexões passadas AGORA MESMO (sem esperar resposta nenhuma) e agenda um
  // alarme rápido (2s, pelo sistema de alarme do PartyKit — não um setTimeout solto dentro
  // do pedido de conexão) pra fechar quem não respondeu até lá. Usado quando alguém tenta
  // entrar numa sala que parece cheia, pra dar uma chance de descobrir se tem conexão
  // fantasma — SEM travar essa pessoa esperando: ela é recusada por enquanto (mesma mensagem
  // de sempre) e, se abriu vaga de verdade, a PRÓXIMA tentativa (poucos segundos depois) já
  // entra. Importante ficar assim, sem `await` de espera aqui dentro: um `setTimeout` preso
  // dentro do próprio pedido de conexão travava a conexão da pessoa em "conectando" pra
  // sempre nesse ambiente — foi um bug de verdade, já visto ao vivo.
  pingForPrune(connections) {
    for (const c of connections) {
      if (this.pendingPings.get(c.id)) continue; // já tem um ping pendente dela, não manda outro
      this.pendingPings.set(c.id, true);
      try { c.send(JSON.stringify({ type: 'ping' })); } catch (e) { this.pendingPings.delete(c.id); try { c.close(); } catch (e2) {} }
    }
  }

  // Escolhe as 3 palavras e manda só pra pessoa que vai desenhar — ninguém mais vê essa
  // mensagem, é entrega direta (igual voiceSignal/screenSignal), não broadcast.
  startWordChoice() {
    const g = this.playback.drawGame;
    const choices = pickThreeWords();
    this.pendingWordChoices = choices;
    const drawer = [...this.room.getConnections()].find((c) => c.state?.clientId === g.currentDrawerId);
    if (drawer) drawer.send(JSON.stringify({ type: 'gameWordChoices', words: choices }));
  }

  // Pula gente que já não está mais conectada (saiu no meio do jogo).
  skipDisconnectedDrawers() {
    const g = this.playback.drawGame;
    const connectedIds = new Set([...this.room.getConnections()].map((c) => c.state?.clientId));
    while (g.order.length && !connectedIds.has(g.order[g.turnIndex])) {
      g.order.splice(g.turnIndex, 1);
      if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
    }
  }

  beginTurn() {
    const g = this.playback.drawGame;
    this.skipDisconnectedDrawers();
    if (g.round > 3 || !g.order.length) {
      g.phase = g.order.length ? 'finished' : 'idle';
      g.currentDrawerId = null;
      g.currentDrawerName = null;
      return;
    }
    g.currentDrawerId = g.order[g.turnIndex];
    g.currentDrawerName = g.names[g.currentDrawerId] || 'Alguém';
    g.phase = 'choosing';
    g.wordLength = 0;
    g.turnStartedAt = null;
    this.startWordChoice();
  }

  // Fecha a rodada da pessoa atual (com ou sem acerto) e passa pra próxima. `guesserId` nulo
  // significa "ninguém acertou a tempo" (chamado pelo cronômetro de 60s).
  advanceTurn(guesserId) {
    const g = this.playback.drawGame;
    const drawerId = g.currentDrawerId;
    if (guesserId) {
      const elapsed = g.turnStartedAt ? (Date.now() - g.turnStartedAt) / 1000 : 60;
      const guesserPoints = Math.max(10, Math.round(100 - (Math.min(elapsed, 60) / 60) * 90));
      const drawerPoints = Math.round(guesserPoints / 2);
      g.scores[guesserId] = (g.scores[guesserId] || 0) + guesserPoints;
      g.scores[drawerId] = (g.scores[drawerId] || 0) + drawerPoints;
      g.lastGuess = { guesserId, guesserName: g.names[guesserId] || 'Alguém', points: guesserPoints, drawerPoints };
    } else {
      g.lastGuess = null;
    }
    this.pendingWordChoices = null;
    g.turnIndex++;
    if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
    this.beginTurn();
  }

  // Pula gente que já não está mais conectada (saiu no meio do jogo).
  skipDisconnectedSetters() {
    const g = this.playback.hangmanGame;
    const connectedIds = new Set([...this.room.getConnections()].map((c) => c.state?.clientId));
    while (g.order.length && !connectedIds.has(g.order[g.turnIndex])) {
      g.order.splice(g.turnIndex, 1);
      if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
    }
  }

  beginHangmanTurn() {
    const g = this.playback.hangmanGame;
    this.skipDisconnectedSetters();
    if (g.round > 3 || !g.order.length) {
      g.phase = g.order.length ? 'finished' : 'idle';
      g.currentSetterId = null;
      g.currentSetterName = null;
      return;
    }
    g.currentSetterId = g.order[g.turnIndex];
    g.currentSetterName = g.names[g.currentSetterId] || 'Alguém';
    g.phase = 'setting';
    g.wordLength = 0;
    g.guessedLetters = [];
    g.wrongLetters = [];
    g.revealedPattern = [];
    g.turnStartedAt = null;
    g.lastRoundResult = null;
    this.hangmanSecretWord = null;
  }

  // Fecha a rodada atual (a palavra foi adivinhada OU estourou as tentativas erradas / o
  // tempo), dá pontos pra quem escolheu a palavra se o grupo ganhou, e agenda a próxima
  // rodada com um tempinho de folga pra todo mundo ver o resultado antes de trocar de vez.
  finishHangmanRound(won) {
    const g = this.playback.hangmanGame;
    const setterId = g.currentSetterId;
    const word = this.hangmanSecretWord;
    if (won) {
      const setterPoints = Math.max(10, Math.round(100 - (g.wrongLetters.length / HANGMAN_MAX_WRONG) * 90));
      g.scores[setterId] = (g.scores[setterId] || 0) + setterPoints;
      g.lastRoundResult = { won: true, word, setterId, setterName: g.names[setterId] || 'Alguém', setterPoints };
    } else {
      g.lastRoundResult = { won: false, word, setterId, setterName: g.names[setterId] || 'Alguém', setterPoints: 0 };
    }
    g.phase = 'roundEnd';
    clearTimeout(this.hangmanTimer);
    this.hangmanSecretWord = null;
    g.turnIndex++;
    if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
    clearTimeout(this.hangmanRoundEndTimer);
    this.hangmanRoundEndTimer = setTimeout(async () => {
      this.beginHangmanTurn();
      await this.persist();
      this.broadcastState();
    }, 3500);
    // mostra o resultado ("ganhou"/"perdeu" + a palavra) na hora — o timer acima só troca
    // pra próxima rodada DEPOIS da folga, não é o mesmo momento.
    this.persist();
    this.broadcastState();
  }

  // Chamado ao (re)acordar a sala — recarrega o que foi salvo antes de hibernar.
  async onStart() {
    const saved = await this.room.storage.get('roomData');
    if (saved) {
      this.created = true;
      this.password = saved.password;
      this.maxPeople = saved.maxPeople;
      // Sala que já existia (e ficou salva) de ANTES de alguma feature nova ser lançada —
      // tipo o Draw Game — foi persistida sem esses campos. Sem esse merge com o estado
      // padrão atual, "this.playback.drawGame" fica undefined nela pra sempre, e qualquer
      // mensagem do jogo (gameInvite etc.) quebra por dentro tentando ler ".phase" de
      // undefined — sem avisar ninguém, simplesmente não acontece nada quando a pessoa
      // clica. Reaproveita o que já tinha e só completa o que faltar.
      this.playback = { ...defaultPlaybackState(), ...saved.playback };
      if (!this.playback.drawGame) this.playback.drawGame = defaultDrawGameState();
    }
  }

  async persist() {
    await this.room.storage.put('roomData', {
      password: this.password,
      maxPeople: this.maxPeople,
      playback: this.playback,
    });
  }

  broadcastState() {
    this.room.broadcast(JSON.stringify({ type: 'state', state: this.playback }));
  }

  broadcastMembers() {
    // clientId vai junto (não só o nome) — é o que o chat de voz usa pra saber com quem
    // abrir uma conexão WebRTC (nomes podem repetir entre pessoas, clientId não).
    const members = [...this.room.getConnections()].map((c) => ({ clientId: c.state?.clientId, name: c.state?.name || 'Convidado' }));
    this.room.broadcast(JSON.stringify({ type: 'members', members, maxPeople: this.maxPeople }));
  }

  async onConnect(connection, ctx) {
    const url = new URL(ctx.request.url);
    const mode = url.searchParams.get('mode') === 'create' ? 'create' : 'join';
    const password = String(url.searchParams.get('password') || '').slice(0, 64);
    const name = (url.searchParams.get('name') || 'Convidado').slice(0, 24);
    // clientId é o id salvo no localStorage do navegador (sobrevive a reconexões);
    // connection.id é gerado do zero a cada conexão nova, não serve pra isso.
    const clientId = url.searchParams.get('id') || connection.id;

    if (mode === 'create') {
      if (this.created) {
        connection.close(CLOSE_ROOM_EXISTS, 'Essa sala já existe. Escolha outro nome ou entre nela.');
        return;
      }
      this.created = true;
      this.password = password;
      this.maxPeople = clampMaxPeople(url.searchParams.get('maxPeople'));
      this.playback = defaultPlaybackState();
    } else {
      if (!this.created) {
        connection.close(CLOSE_ROOM_MISSING, 'Essa sala não existe. Confira o código ou crie uma nova.');
        return;
      }
      if (this.password && this.password !== password) {
        connection.close(CLOSE_WRONG_PASSWORD, 'Senha incorreta.');
        return;
      }
      // reconexão do mesmo dispositivo não deve contar contra o limite de vagas
      const others = [...this.room.getConnections()].filter((c) => c.id !== connection.id);
      const isReconnect = others.some((c) => c.state?.clientId === clientId);
      if (!isReconnect && others.length + 1 > this.maxPeople) {
        // Sala parece cheia — dá uma chance de descobrir se tem conexão fantasma pendurada
        // (rede caiu sem avisar, notebook foi dormir...) antes de recusar de vez pra sempre.
        // Sem isso, uma sala que já ficou lotada de fantasma nunca mais deixaria ninguém
        // entrar (o batimento cardíaco periódico só roda enquanto já tem gente conectada de
        // propósito — uma sala 100% travada nunca chegaria a rodar ele sozinha). Manda o ping
        // e agenda a checagem via alarme (ver pingForPrune) SEM esperar aqui — essa tentativa
        // é recusada por enquanto; se abriu vaga, a PRÓXIMA (poucos segundos depois) já entra.
        this.pingForPrune(others);
        await this.room.storage.setAlarm(Date.now() + 2000);
        connection.close(CLOSE_ROOM_FULL, `Sala cheia (máximo de ${this.maxPeople} pessoas).`);
        return;
      }
      // Reconexão rápida (rede caiu e voltou sozinha — comum durante compartilhamento de
      // tela, que pesa bastante na CPU) podia deixar a conexão VELHA pendurada junto com a
      // nova, as duas com o mesmo clientId. Isso fazia a pessoa aparecer duplicada na lista
      // (mesmo nome, mesmo avatar, mas uma delas "fantasma") e podia fazer sinalização de
      // voz/tela ser roteada pra conexão morta em vez da nova — daí a outra pessoa não
      // conseguir ver a tela compartilhada. Fecha qualquer conexão antiga com esse mesmo
      // clientId antes de aceitar a nova, garantindo no máximo uma por pessoa sempre.
      for (const c of others) {
        if (c.state?.clientId === clientId) { try { c.close(); } catch (e) { /* já pode ter caído sozinha */ } }
      }
    }

    connection.setState({ clientId, name });
    await this.persist();

    connection.send(JSON.stringify({ type: 'state', state: this.playback }));
    this.broadcastMembers();
    // garante que o "batimento cardíaco" (ver checkHeartbeat) tá rodando — inofensivo chamar
    // de novo se já tinha um agendado, só empurra o próximo ciclo pra frente.
    await this.room.storage.setAlarm(Date.now() + 20000);
  }

  async onMessage(raw, sender) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return;
    }
    const s = this.playback;
    const name = sender.state?.name || 'Convidado';
    let changed = true;

    switch (msg.type) {
      case 'addQueue': {
        if (!msg.videoId) { changed = false; break; }
        s.queue.push({
          qid: genId(),
          videoId: String(msg.videoId).slice(0, 20),
          title: String(msg.title || 'Vídeo').slice(0, 200),
          thumb: String(msg.thumb || ''),
          artist: String(msg.artist || '').slice(0, 120), // canal/artista (oEmbed) — usado na busca de letra
          addedBy: name,
          isLive: !!msg.isLive, // transmissão ao vivo não tem posição fixa pra sincronizar (ver driftCorrect no cliente)
        });
        if (s.currentIndex === -1) s.currentIndex = s.queue.length - 1;
        break;
      }
      case 'removeQueue': {
        const idx = s.queue.findIndex((q) => q.qid === msg.qid);
        if (idx < 0) { changed = false; break; }
        s.queue.splice(idx, 1);
        if (idx < s.currentIndex) s.currentIndex--;
        else if (idx === s.currentIndex) {
          s.isPlaying = false;
          s.position = 0;
          s.currentIndex = s.queue.length ? Math.min(idx, s.queue.length - 1) : -1;
        }
        break;
      }
      case 'moveQueue': {
        const idx = s.queue.findIndex((q) => q.qid === msg.qid);
        const newIdx = idx + msg.dir;
        if (idx < 0 || newIdx < 0 || newIdx >= s.queue.length) { changed = false; break; }
        const playingQid = s.currentIndex >= 0 ? s.queue[s.currentIndex]?.qid : null;
        [s.queue[idx], s.queue[newIdx]] = [s.queue[newIdx], s.queue[idx]];
        if (playingQid) s.currentIndex = s.queue.findIndex((q) => q.qid === playingQid);
        break;
      }
      case 'playIndex': {
        if (msg.index < 0 || msg.index >= s.queue.length) { changed = false; break; }
        s.currentIndex = msg.index;
        s.isPlaying = true;
        s.position = 0;
        s.updatedAt = Date.now();
        s.hostName = name;
        break;
      }
      case 'play': {
        if (s.currentIndex === -1) { changed = false; break; }
        s.isPlaying = true;
        s.updatedAt = Date.now();
        s.hostName = name;
        break;
      }
      case 'pause': {
        if (s.currentIndex === -1) { changed = false; break; }
        s.position = computeEstimatedPosition(s);
        s.isPlaying = false;
        s.updatedAt = Date.now();
        s.hostName = name;
        break;
      }
      case 'seek': {
        if (s.currentIndex === -1) { changed = false; break; }
        s.position = Math.max(0, computeEstimatedPosition(s) + Number(msg.delta || 0));
        s.updatedAt = Date.now();
        s.hostName = name;
        break;
      }
      case 'rename': {
        const newName = String(msg.name || name).slice(0, 24);
        sender.setState({ ...sender.state, name: newName });
        this.broadcastMembers();
        changed = false;
        break;
      }
      // ---------------- chat de texto ----------------
      // Vai junto no `playback` igual à fila — broadcast simples pra sala toda a cada mensagem.
      // Guarda só as últimas 100 pra não crescer pra sempre numa festa longa.
      case 'chatMessage': {
        const text = String(msg.text || '').trim().slice(0, 300);
        if (!text) { changed = false; break; }
        s.chatLog.push({ id: genId(), clientId: sender.state?.clientId, name, text, ts: Date.now() });
        if (s.chatLog.length > 100) s.chatLog.splice(0, s.chatLog.length - 100);
        break;
      }
      // ---------------- playlists salvas da sala ----------------
      // Ficam guardadas de forma independente da fila (cada uma é uma "foto" de quando foi
      // salva) — mexer na fila depois não muda a playlist salva até alguém clicar em salvar
      // de novo por cima dela.
      case 'savePlaylist': {
        changed = false;
        const playlistName = String(msg.name || '').trim().slice(0, 40);
        if (!playlistName) break;
        const snapshot = () => s.queue.map((q) => ({
          videoId: q.videoId, title: q.title, thumb: q.thumb, artist: q.artist || '', isLive: !!q.isLive, addedBy: q.addedBy,
        }));
        if (msg.action === 'update' && msg.playlistId) {
          const pl = s.playlists.find((p) => p.id === msg.playlistId);
          if (!pl) break;
          pl.name = playlistName;
          pl.items = snapshot();
          s.activePlaylistId = pl.id; // continua "aberta" depois de salvar em cima dela
          changed = true;
        } else {
          if (!s.queue.length || s.playlists.length >= MAX_PLAYLISTS) break;
          const pl = { id: genId(), name: playlistName, items: snapshot() };
          s.playlists.push(pl);
          s.activePlaylistId = pl.id; // acabou de nascer, já é a que tá "aberta"
          changed = true;
        }
        break;
      }
      // playlistId vazio = só "soltar" da playlist atual (virar fila solta de novo), sem
      // mexer em nada da fila — é o que a opção "Fila atual" do dropdown manda.
      case 'loadPlaylist': {
        changed = false;
        if (!msg.playlistId) { s.activePlaylistId = null; changed = true; break; }
        const pl = s.playlists.find((p) => p.id === msg.playlistId);
        if (!pl) break;
        s.queue = pl.items.map((it) => ({
          qid: genId(), videoId: it.videoId, title: it.title, thumb: it.thumb,
          artist: it.artist || '', addedBy: it.addedBy || name, isLive: !!it.isLive,
        }));
        s.currentIndex = -1;
        s.isPlaying = false;
        s.position = 0;
        s.updatedAt = Date.now();
        s.activePlaylistId = pl.id;
        changed = true;
        break;
      }
      case 'deletePlaylist': {
        changed = false;
        const idx = s.playlists.findIndex((p) => p.id === msg.playlistId);
        if (idx < 0) break;
        s.playlists.splice(idx, 1);
        if (s.activePlaylistId === msg.playlistId) s.activePlaylistId = null;
        changed = true;
        break;
      }
      // ---------------- chat de voz (WebRTC) ----------------
      // O servidor NUNCA vê nem toca em áudio — só entrega mensagens de sinalização (SDP/ICE)
      // de um cliente pro outro específico, pra eles montarem a conexão P2P direto entre si.
      case 'voiceSignal': {
        changed = false;
        const target = [...this.room.getConnections()].find((c) => c.state?.clientId === msg.to);
        if (target) {
          target.send(JSON.stringify({ type: 'voiceSignal', from: sender.state?.clientId, signal: msg.signal }));
        }
        break;
      }
      // Só um aviso pra UI (mostrar o "🔴 falando" do lado do nome) — não carrega áudio nenhum,
      // é broadcast pra sala toda igual à fila/estado do player.
      case 'voiceStatus': {
        changed = false;
        this.room.broadcast(JSON.stringify({ type: 'voiceStatus', clientId: sender.state?.clientId, speaking: !!msg.speaking }));
        break;
      }
      // ---------------- compartilhar tela (WebRTC, mesma ideia do chat de voz) ----------------
      // Só uma pessoa por vez — quem já está compartilhando "trava" o campo no estado da sala,
      // que é sincronizado igual à fila/player, então todo mundo vê quem está compartilhando.
      case 'startScreenShare': {
        const myId = sender.state?.clientId;
        if (s.screenSharerId && s.screenSharerId !== myId) { changed = false; break; }
        s.screenSharerId = myId;
        s.screenSharerName = name;
        break;
      }
      case 'stopScreenShare': {
        if (s.screenSharerId !== sender.state?.clientId) { changed = false; break; }
        s.screenSharerId = null;
        s.screenSharerName = null;
        break;
      }
      // Sinalização (SDP/ICE) da tela compartilhada — mesmo princípio do voiceSignal: o
      // servidor só entrega pro destinatário certo, nunca vê o conteúdo da tela em si.
      case 'screenSignal': {
        changed = false;
        const target = [...this.room.getConnections()].find((c) => c.state?.clientId === msg.to);
        if (target) {
          target.send(JSON.stringify({ type: 'screenSignal', from: sender.state?.clientId, signal: msg.signal }));
        }
        break;
      }
      // ---------------- jogo de desenho ----------------
      case 'gameInvite': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'idle') break;
        const connectedIds = new Set([...this.room.getConnections()].map((c) => c.state?.clientId));
        const invited = Array.isArray(msg.to) ? msg.to.filter((id) => connectedIds.has(id) && id !== myId).slice(0, 49) : [];
        if (!invited.length) break;
        g.phase = 'inviting';
        g.hostId = myId;
        g.invitedIds = invited;
        g.acceptedIds = [myId];
        g.names[myId] = name;
        changed = true;
        break;
      }
      case 'gameRespond': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'inviting' || !g.invitedIds.includes(myId)) break;
        g.invitedIds = g.invitedIds.filter((id) => id !== myId);
        if (msg.accept) {
          if (!g.acceptedIds.includes(myId)) g.acceptedIds.push(myId);
          g.names[myId] = name;
        }
        changed = true;
        break;
      }
      case 'gameBegin': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'inviting' || myId !== g.hostId || g.acceptedIds.length < 2) break;
        g.order = shuffleArray(g.acceptedIds);
        g.invitedIds = [];
        g.round = 1;
        g.turnIndex = 0;
        g.scores = {};
        for (const id of g.order) g.scores[id] = 0;
        this.beginTurn();
        changed = true;
        break;
      }
      case 'gameChooseWord': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'choosing' || myId !== g.currentDrawerId) break;
        const choices = this.pendingWordChoices;
        if (!choices || !choices.includes(msg.word)) break;
        this.pendingWordChoices = null;
        g.wordLength = String(msg.word).length;
        g.turnStartedAt = Date.now();
        g.phase = 'drawing';
        clearTimeout(this.turnTimer);
        this.turnTimer = setTimeout(() => { this.advanceTurn(null); this.persist(); this.broadcastState(); }, 60000);
        changed = true;
        break;
      }
      case 'gameStroke': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'drawing' || myId !== g.currentDrawerId) break;
        const payload = JSON.stringify({ type: 'gameStroke', points: msg.points, color: msg.color, width: msg.width, newStroke: !!msg.newStroke });
        for (const c of this.room.getConnections()) {
          if (c.state?.clientId !== myId && g.acceptedIds.includes(c.state?.clientId)) c.send(payload);
        }
        break;
      }
      case 'gameClearCanvas': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'drawing' || myId !== g.currentDrawerId) break;
        const payload = JSON.stringify({ type: 'gameClearCanvas' });
        for (const c of this.room.getConnections()) {
          if (c.state?.clientId !== myId && g.acceptedIds.includes(c.state?.clientId)) c.send(payload);
        }
        break;
      }
      case 'gameGuessed': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'drawing' || myId !== g.currentDrawerId) break;
        if (!g.acceptedIds.includes(msg.guesserId) || msg.guesserId === myId) break;
        clearTimeout(this.turnTimer);
        this.advanceTurn(msg.guesserId);
        changed = true;
        break;
      }
      case 'gameCancel': {
        changed = false;
        const g = s.drawGame;
        const myId = sender.state?.clientId;
        if (g.phase === 'idle' || myId !== g.hostId) break;
        clearTimeout(this.turnTimer);
        this.pendingWordChoices = null;
        s.drawGame = defaultDrawGameState();
        changed = true;
        break;
      }
      // ---------------- forca (jogo de adivinhar palavra em grupo) ----------------
      case 'hangmanInvite': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase !== 'idle') break;
        const connectedIds = new Set([...this.room.getConnections()].map((c) => c.state?.clientId));
        const invited = Array.isArray(msg.to) ? msg.to.filter((id) => connectedIds.has(id) && id !== myId).slice(0, 49) : [];
        if (!invited.length) break;
        h.phase = 'inviting';
        h.hostId = myId;
        h.invitedIds = invited;
        h.acceptedIds = [myId];
        h.names[myId] = name;
        changed = true;
        break;
      }
      case 'hangmanRespond': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase !== 'inviting' || !h.invitedIds.includes(myId)) break;
        h.invitedIds = h.invitedIds.filter((id) => id !== myId);
        if (msg.accept) {
          if (!h.acceptedIds.includes(myId)) h.acceptedIds.push(myId);
          h.names[myId] = name;
        }
        changed = true;
        break;
      }
      case 'hangmanBegin': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase !== 'inviting' || myId !== h.hostId || h.acceptedIds.length < 2) break;
        h.order = shuffleArray(h.acceptedIds);
        h.invitedIds = [];
        h.round = 1;
        h.turnIndex = 0;
        h.scores = {};
        for (const id of h.order) h.scores[id] = 0;
        this.beginHangmanTurn();
        changed = true;
        break;
      }
      case 'hangmanSetWord': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase !== 'setting' || myId !== h.currentSetterId) break;
        const word = String(msg.word || '').trim();
        if (!/^[a-zA-ZÀ-ÿ]{3,20}$/.test(word)) break;
        this.hangmanSecretWord = word;
        h.wordLength = word.length;
        h.revealedPattern = [...word].map(() => null);
        h.turnStartedAt = Date.now();
        h.phase = 'playing';
        clearTimeout(this.hangmanTimer);
        this.hangmanTimer = setTimeout(() => { this.finishHangmanRound(false); }, 90000);
        changed = true;
        break;
      }
      case 'hangmanGuessLetter': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase !== 'playing' || myId === h.currentSetterId) break;
        const letter = normalizeLetter(msg.letter).slice(0, 1);
        if (!letter || !/^[a-z]$/.test(letter) || h.guessedLetters.includes(letter)) break;
        h.guessedLetters.push(letter);
        const secretWord = this.hangmanSecretWord || '';
        const normalizedWord = normalizeLetter(secretWord);
        if (normalizedWord.includes(letter)) {
          h.scores[myId] = (h.scores[myId] || 0) + 15;
          h.revealedPattern = [...secretWord].map((ch) => (h.guessedLetters.includes(normalizeLetter(ch)) ? ch : null));
          const allGuessed = [...normalizedWord].every((c) => h.guessedLetters.includes(c));
          if (allGuessed) { this.finishHangmanRound(true); break; }
        } else {
          h.wrongLetters.push(letter);
          if (h.wrongLetters.length >= HANGMAN_MAX_WRONG) { this.finishHangmanRound(false); break; }
        }
        changed = true;
        break;
      }
      case 'hangmanCancel': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase === 'idle') break;
        // cancelar um CONVITE ainda é só de quem convidou (é a partida dele) — mas depois que
        // o jogo já começou de verdade, qualquer um pode reiniciar. Serve de "escape" pra
        // quando o jogo trava (ex: o anfitrião caiu da sala e não tem mais como ele mesmo
        // cancelar) — sem isso, o jogo ficava preso pra sempre nesse caso.
        if (h.phase === 'inviting' && myId !== h.hostId) break;
        clearTimeout(this.hangmanTimer);
        clearTimeout(this.hangmanRoundEndTimer);
        this.hangmanSecretWord = null;
        s.hangmanGame = defaultHangmanState();
        changed = true;
        break;
      }
      // ---------------- roleta de categorias ----------------
      case 'stopSetTheme': {
        s.stopGame.theme = String(msg.theme || '').trim().slice(0, 60);
        changed = true;
        break;
      }
      case 'stopPickLetter': {
        changed = false;
        const g = s.stopGame;
        const letter = String(msg.letter || '').toUpperCase().slice(0, 1);
        if (!/^[A-Z]$/.test(letter) || g.usedLetters.includes(letter)) break;
        g.usedLetters.push(letter);
        g.currentLetter = letter;
        g.roundStartedAt = Date.now();
        changed = true;
        break;
      }
      case 'stopNextLetter': {
        changed = false;
        const g = s.stopGame;
        if (!g.currentLetter) break;
        g.currentLetter = null;
        g.roundStartedAt = null;
        changed = true;
        break;
      }
      case 'stopReset': {
        s.stopGame = defaultStopGameState();
        changed = true;
        break;
      }
      // resposta do "batimento cardíaco" (ver checkHeartbeat) — só confirma que essa conexão
      // ainda tá viva, não muda nada do estado da sala.
      case 'pong': {
        this.pendingPings.delete(sender.id);
        changed = false;
        break;
      }
      default:
        changed = false;
    }

    if (changed) {
      await this.persist();
      this.broadcastState();
    }
  }

  async onClose(connection) {
    this.pendingPings.delete(connection.id);
    // Se já existe OUTRA conexão viva com esse mesmo clientId, essa desconexão aqui é só a
    // conexão velha (fantasma) morrendo depois de uma reconexão rápida — a pessoa continua
    // na sala de verdade pela conexão nova. Não faz nenhuma faxina de "saiu da sala" nesse
    // caso (senão ela seria tirada do jogo/tela compartilhada à toa mesmo continuando aqui);
    // só atualiza a lista de membros, que já reflete certinho quem ainda está conectado.
    const myClientId = connection.state?.clientId;
    const stillConnected = [...this.room.getConnections()].some((c) => c.id !== connection.id && c.state?.clientId === myClientId);
    if (stillConnected) { this.broadcastMembers(); return; }
    if (this.playback.screenSharerId === connection.state?.clientId) {
      // quem tava compartilhando a tela caiu/saiu — libera o campo pra outra pessoa poder compartilhar
      this.playback.screenSharerId = null;
      this.playback.screenSharerName = null;
      await this.persist();
      this.broadcastState();
    }
    const clientId = connection.state?.clientId;
    const g = this.playback.drawGame;
    if (g.phase !== 'idle' && (g.acceptedIds.includes(clientId) || g.invitedIds.includes(clientId))) {
      g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
      g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
      if (g.phase === 'inviting') {
        if (clientId === g.hostId) this.playback.drawGame = defaultDrawGameState();
      } else if (g.currentDrawerId === clientId) {
        clearTimeout(this.turnTimer);
        this.advanceTurn(null);
      } else {
        g.order = g.order.filter((id) => id !== clientId);
      }
      await this.persist();
      this.broadcastState();
    }
    const h = this.playback.hangmanGame;
    if (h.phase !== 'idle' && (h.acceptedIds.includes(clientId) || h.invitedIds.includes(clientId))) {
      h.invitedIds = h.invitedIds.filter((id) => id !== clientId);
      h.acceptedIds = h.acceptedIds.filter((id) => id !== clientId);
      if (h.phase === 'inviting') {
        if (clientId === h.hostId) this.playback.hangmanGame = defaultHangmanState();
        await this.persist();
        this.broadcastState();
      } else if (h.currentSetterId === clientId) {
        // quem tava com a vez caiu — fecha a rodada como "ninguém acertou" (finishHangmanRound
        // já persiste e faz o broadcast sozinho).
        clearTimeout(this.hangmanTimer);
        this.finishHangmanRound(false);
      } else {
        h.order = h.order.filter((id) => id !== clientId);
        await this.persist();
        this.broadcastState();
      }
    }
    // não precisa de faxina manual de sala vazia aqui (o server.js original apagava a
    // sala da memória 10min depois de ficar vazia) — o próprio PartyKit já hiberna a
    // sala sozinho quando ninguém está conectado.
    this.broadcastMembers();
  }
}
