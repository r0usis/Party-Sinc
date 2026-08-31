// server.js — Festa Sync (servidor local)
// Guarda o estado de cada "sala" em memória e sincroniza todo mundo em tempo real via WebSocket.
// Roda com: npm install && npm start

import express from 'express';
import { WebSocketServer } from 'ws';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
// Sem isso, o navegador (e qualquer proxy/CDN no meio do caminho) guarda o index.html em cache
// e um F5 normal continua mostrando a versão velha depois de cada atualização — só um
// Ctrl+Shift+R (que ignora o cache) pega a nova. `no-cache` aqui não é "nunca guarda", é
// "sempre confere com o servidor antes de usar o que tá guardado" — então F5 normal já
// resolve, sem precisar do atalho de recarregar ignorando cache.
app.use(express.static(path.join(__dirname, 'public'), {
  setHeaders: (res) => res.setHeader('Cache-Control', 'no-cache'),
}));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// rooms: Map<codigoDaSala, { state, clients: Map<clientId, {ws, name}>, password, maxPeople }>
// Sala só existe depois de alguém criar explicitamente (não é mais auto-criada no primeiro join) —
// é o que permite ter senha e limite de gente: sem isso, qualquer um digitando o código "criava"
// a sala na hora e a senha/capacidade nunca teriam chance de valer.
const rooms = new Map();

// Códigos de fechamento customizados que o cliente usa pra saber POR QUE não entrou — nada de
// tentar reconectar sozinho nesses casos (ver client: SALA_REJECT_CODES).
const CLOSE_ROOM_EXISTS = 4001; // tentou criar uma sala com nome que já existe
const CLOSE_ROOM_MISSING = 4002; // tentou entrar numa sala que não existe
const CLOSE_WRONG_PASSWORD = 4003; // senha errada
const CLOSE_ROOM_FULL = 4004; // já bateu no limite de pessoas

function defaultRoomState() {
  return {
    queue: [], currentIndex: -1, isPlaying: false, position: 0, updatedAt: Date.now(), hostName: null,
    screenSharerId: null, screenSharerName: null, // quem está compartilhando a tela agora (só uma pessoa por vez)
    drawGame: defaultDrawGameState(),
    hangmanGame: defaultHangmanState(),
    stopGame: defaultStopGameState(),
    chatLog: [], // mensagens de texto da sala — guarda um histórico curto pra quem entra depois também ver
    playlists: [], // listas de música salvas da sala — sobrevivem pra quem entrar depois
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
    lastGuess: null, // { guesserId, guesserName, points, drawerPoints } — só pra mostrar um "aviso" rápido
  };
}

// ---------------- forca (jogo de adivinhar palavra em grupo) ----------------
const HANGMAN_MAX_WRONG = 6; // tentativas erradas antes de "morrer" (bate com os estágios do desenho no cliente)
// Tira acento pra comparar letra (ex: "e" acerta tanto "e" quanto "é") — do jeito que a
// maioria dos jogos de forca em português já funciona, sem forçar quem tá jogando a
// adivinhar o acento certinho letra por letra.
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
    guessedLetters: [], // letras (sem acento) já tentadas por qualquer um do grupo, certas ou erradas
    wrongLetters: [],   // subconjunto de guessedLetters que erraram — o que desenha o boneco
    revealedPattern: [], // uma posição por letra da palavra: a letra de verdade se já foi acertada, null se ainda não
    turnStartedAt: null,
    scores: {},
    names: {},
    lastRoundResult: null, // { won, word, setterId, setterName, setterPoints } — só pra mostrar o resultado
  };
}

// ---------------- roleta de categorias (tipo "Stop"/Adedanha) ----------------
// Bem mais simples que os outros dois: ninguém convida ninguém, é um "quadro" compartilhado
// que qualquer um na sala pode mexer — o tema é livre (a galera decide e digita, não vem de
// banco nenhum) e ninguém precisa DIGITAR a resposta no app (é falado por voz, o app só
// cuida da roleta de letras e do cronômetro de 30s visível pra todo mundo).
function defaultStopGameState() {
  return {
    theme: '', // tema livre, decidido por quem tiver jogando
    usedLetters: [], // letras (A-Z) já sorteadas
    currentLetter: null, // letra da vez agora (null = ninguém escolheu ainda / voltou pra roleta)
    roundStartedAt: null, // quando essa letra começou a valer — dá pro cronômetro de 30s ser igual pra todo mundo
  };
}

function clampMaxPeople(raw) {
  const n = parseInt(raw, 10);
  if (!Number.isFinite(n)) return 10;
  return Math.min(50, Math.max(2, n));
}

function getRoom(code) {
  return rooms.get(code);
}

function computeEstimatedPosition(state) {
  if (state.currentIndex < 0) return 0;
  if (!state.isPlaying) return state.position;
  return state.position + (Date.now() - state.updatedAt) / 1000;
}

function genId() {
  return Math.random().toString(36).slice(2, 10);
}

function broadcastState(code) {
  const room = rooms.get(code);
  if (!room) return;
  const payload = JSON.stringify({ type: 'state', state: room.state });
  for (const { ws } of room.clients.values()) {
    if (ws.readyState === ws.OPEN) ws.send(payload);
  }
}

// Escolhe as 3 palavras e manda só pra pessoa que vai desenhar — ninguém mais vê essa
// mensagem, é entrega direta (igual voiceSignal/screenSignal), não broadcast.
function startWordChoice(room2) {
  const g = room2.state.drawGame;
  const choices = pickThreeWords();
  room2.pendingWordChoices = choices;
  const drawer = room2.clients.get(g.currentDrawerId);
  if (drawer && drawer.ws.readyState === drawer.ws.OPEN) {
    drawer.ws.send(JSON.stringify({ type: 'gameWordChoices', words: choices }));
  }
}

// Pula gente que já não está mais conectada (saiu no meio do jogo) — sem isso o jogo
// ficaria esperando pra sempre alguém que nunca vai escolher uma palavra.
function skipDisconnectedDrawers(room2) {
  const g = room2.state.drawGame;
  while (g.order.length && !room2.clients.has(g.order[g.turnIndex])) {
    g.order.splice(g.turnIndex, 1);
    if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
  }
}

function beginTurn(room2) {
  const g = room2.state.drawGame;
  skipDisconnectedDrawers(room2);
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
  startWordChoice(room2);
}

// Fecha a rodada da pessoa atual (com ou sem acerto) e passa pra próxima. `guesserId` nulo
// significa "ninguém acertou a tempo" (chamado pelo cronômetro de 60s).
function advanceTurn(room2, guesserId) {
  const g = room2.state.drawGame;
  const drawerId = g.currentDrawerId;
  if (guesserId) {
    const elapsed = g.turnStartedAt ? (Date.now() - g.turnStartedAt) / 1000 : 60;
    // quanto mais rápido, mais pontos — de 100 (acerto instantâneo) até 10 (quase no limite dos 60s)
    const guesserPoints = Math.max(10, Math.round(100 - (Math.min(elapsed, 60) / 60) * 90));
    const drawerPoints = Math.round(guesserPoints / 2); // quem desenhou ganha metade do que o adivinhador ganhou
    g.scores[guesserId] = (g.scores[guesserId] || 0) + guesserPoints;
    g.scores[drawerId] = (g.scores[drawerId] || 0) + drawerPoints;
    g.lastGuess = { guesserId, guesserName: g.names[guesserId] || 'Alguém', points: guesserPoints, drawerPoints };
  } else {
    g.lastGuess = null;
  }
  room2.pendingWordChoices = null;
  room2.secretWord = null;
  g.turnIndex++;
  if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
  beginTurn(room2);
}

// Pula gente que já não está mais conectada (saiu no meio do jogo).
function skipDisconnectedSetters(room2) {
  const g = room2.state.hangmanGame;
  while (g.order.length && !room2.clients.has(g.order[g.turnIndex])) {
    g.order.splice(g.turnIndex, 1);
    if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
  }
}

function beginHangmanTurn(room2) {
  const g = room2.state.hangmanGame;
  skipDisconnectedSetters(room2);
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
  room2.hangmanSecretWord = null;
}

// Fecha a rodada atual (a palavra foi adivinhada OU estourou as tentativas erradas / o tempo),
// dá pontos pra quem escolheu a palavra se o grupo ganhou, e agenda a próxima rodada com um
// tempinho de folga pra todo mundo ver o resultado (a palavra revelada) antes de trocar de vez.
function finishHangmanRound(room2, room, won) {
  const g = room2.state.hangmanGame;
  const setterId = g.currentSetterId;
  const word = room2.hangmanSecretWord;
  if (won) {
    // menos erros = palavra mais "gostosa" de adivinhar = mais pontos pra quem escolheu ela
    const setterPoints = Math.max(10, Math.round(100 - (g.wrongLetters.length / HANGMAN_MAX_WRONG) * 90));
    g.scores[setterId] = (g.scores[setterId] || 0) + setterPoints;
    g.lastRoundResult = { won: true, word, setterId, setterName: g.names[setterId] || 'Alguém', setterPoints };
  } else {
    g.lastRoundResult = { won: false, word, setterId, setterName: g.names[setterId] || 'Alguém', setterPoints: 0 };
  }
  g.phase = 'roundEnd';
  clearTimeout(room2.hangmanTimer);
  room2.hangmanSecretWord = null;
  g.turnIndex++;
  if (g.turnIndex >= g.order.length) { g.turnIndex = 0; g.round++; }
  clearTimeout(room2.hangmanRoundEndTimer);
  room2.hangmanRoundEndTimer = setTimeout(() => { beginHangmanTurn(room2); broadcastState(room); }, 3500);
  broadcastState(room);
}

function broadcastMembers(code) {
  const room = rooms.get(code);
  if (!room) return;
  // clientId vai junto (não só o nome) — é o que o chat de voz usa pra saber com quem
  // abrir uma conexão WebRTC (nomes podem repetir entre pessoas, clientId não).
  const members = [...room.clients.entries()].map(([clientId, c]) => ({ clientId, name: c.name }));
  const payload = JSON.stringify({ type: 'members', members, maxPeople: room.maxPeople });
  for (const { ws } of room.clients.values()) {
    if (ws.readyState === ws.OPEN) ws.send(payload);
  }
}

function sanitizeRoomCode(raw) {
  return (raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12);
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  // aceita tanto ?room=CODIGO (formato antigo) quanto /parties/main/CODIGO (formato que o
  // cliente usa pra também funcionar direto contra o PartyKit, sem precisar de dois clientes)
  const roomFromPath = url.pathname.split('/').filter(Boolean).pop();
  const room = sanitizeRoomCode(url.searchParams.get('room') || roomFromPath);
  const mode = url.searchParams.get('mode') === 'create' ? 'create' : 'join';
  const password = String(url.searchParams.get('password') || '').slice(0, 64);
  let name = (url.searchParams.get('name') || 'Convidado').slice(0, 24);
  const clientId = url.searchParams.get('id') || genId();

  if (!room) { ws.close(CLOSE_ROOM_MISSING, 'Código de sala inválido.'); return; }

  if (mode === 'create') {
    if (rooms.has(room)) { ws.close(CLOSE_ROOM_EXISTS, 'Essa sala já existe. Escolha outro nome ou entre nela.'); return; }
    rooms.set(room, {
      state: defaultRoomState(),
      clients: new Map(),
      password,
      maxPeople: clampMaxPeople(url.searchParams.get('maxPeople')),
      pendingWordChoices: null, // as 3 palavras oferecidas ao desenhista atual — só o servidor sabe
      turnTimer: null, // cronômetro dos 60s da rodada, pra avançar sozinho se ninguém acertar
      hangmanSecretWord: null, // a palavra da forca da rodada atual — só o servidor sabe
      hangmanTimer: null, // cronômetro da rodada (tempo esgotado = ninguém acertou)
      hangmanRoundEndTimer: null, // folga pra mostrar o resultado antes de trocar de rodada
    });
  } else {
    const existing = rooms.get(room);
    if (!existing) { ws.close(CLOSE_ROOM_MISSING, 'Essa sala não existe. Confira o código ou crie uma nova.'); return; }
    if (existing.password && existing.password !== password) { ws.close(CLOSE_WRONG_PASSWORD, 'Senha incorreta.'); return; }
    // reconexão do mesmo dispositivo não deve contar contra o limite de vagas
    if (!existing.clients.has(clientId) && existing.clients.size >= existing.maxPeople) {
      ws.close(CLOSE_ROOM_FULL, `Sala cheia (máximo de ${existing.maxPeople} pessoas).`);
      return;
    }
  }

  const r = getRoom(room);
  // Reconexão rápida (rede caiu e voltou sozinha — comum durante compartilhamento de tela,
  // que pesa bastante na CPU) podia deixar a conexão VELHA pendurada: o Map já sobrescreve a
  // entrada de "quem tá na sala" com a nova (mesmo clientId), mas o socket velho em si
  // continuava aberto até cair sozinho, e o handler de 'close' DELE podia acabar apagando a
  // entrada da conexão NOVA por engano (mesmo clientId, closure antiga). Fecha o socket velho
  // explicitamente agora, e o guard lá embaixo em ws.on('close') cobre qualquer corrida que
  // ainda escape disso.
  const existing = r.clients.get(clientId);
  if (existing && existing.ws !== ws && existing.ws.readyState === existing.ws.OPEN) {
    try { existing.ws.close(4005, 'Nova conexão do mesmo dispositivo'); } catch (e) { /* já pode ter caído sozinha */ }
  }
  r.clients.set(clientId, { ws, name });

  // manda o estado atual + membros pro recém-chegado
  ws.send(JSON.stringify({ type: 'state', state: r.state }));
  broadcastMembers(room);

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return;
    }
    const room2 = getRoom(room);
    const s = room2.state;
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
        name = String(msg.name || name).slice(0, 24);
        room2.clients.set(clientId, { ws, name });
        broadcastMembers(room);
        changed = false;
        break;
      }
      // ---------------- chat de texto ----------------
      // Vai junto no `state` igual à fila — broadcast simples pra sala toda a cada mensagem.
      // Guarda só as últimas 100 pra não crescer pra sempre numa festa longa.
      case 'chatMessage': {
        const text = String(msg.text || '').trim().slice(0, 300);
        if (!text) { changed = false; break; }
        s.chatLog.push({ id: genId(), clientId, name, text, ts: Date.now() });
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
        const target = room2.clients.get(msg.to);
        if (target && target.ws.readyState === target.ws.OPEN) {
          target.ws.send(JSON.stringify({ type: 'voiceSignal', from: clientId, signal: msg.signal }));
        }
        break;
      }
      // Só um aviso pra UI (mostrar o "🔴 falando" do lado do nome) — não carrega áudio nenhum,
      // é broadcast pra sala toda igual à fila/estado do player.
      case 'voiceStatus': {
        changed = false;
        const payload = JSON.stringify({ type: 'voiceStatus', clientId, speaking: !!msg.speaking });
        for (const { ws } of room2.clients.values()) {
          if (ws.readyState === ws.OPEN) ws.send(payload);
        }
        break;
      }
      // ---------------- compartilhar tela (WebRTC, mesma ideia do chat de voz) ----------------
      // Só uma pessoa por vez — quem já está compartilhando "trava" o campo no estado da sala,
      // que é sincronizado igual à fila/player, então todo mundo vê quem está compartilhando.
      case 'startScreenShare': {
        if (s.screenSharerId && s.screenSharerId !== clientId) { changed = false; break; }
        s.screenSharerId = clientId;
        s.screenSharerName = name;
        break;
      }
      case 'stopScreenShare': {
        if (s.screenSharerId !== clientId) { changed = false; break; }
        s.screenSharerId = null;
        s.screenSharerName = null;
        break;
      }
      // Sinalização (SDP/ICE) da tela compartilhada — mesmo princípio do voiceSignal: o
      // servidor só entrega pro destinatário certo, nunca vê o conteúdo da tela em si.
      case 'screenSignal': {
        changed = false;
        const target = room2.clients.get(msg.to);
        if (target && target.ws.readyState === target.ws.OPEN) {
          target.ws.send(JSON.stringify({ type: 'screenSignal', from: clientId, signal: msg.signal }));
        }
        break;
      }
      // ---------------- jogo de desenho ----------------
      case 'gameInvite': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'idle') break;
        const invited = Array.isArray(msg.to) ? msg.to.filter((id) => room2.clients.has(id) && id !== clientId).slice(0, 49) : [];
        if (!invited.length) break;
        g.phase = 'inviting';
        g.hostId = clientId;
        g.invitedIds = invited;
        g.acceptedIds = [clientId];
        g.names[clientId] = name;
        changed = true;
        break;
      }
      case 'gameRespond': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'inviting' || !g.invitedIds.includes(clientId)) break;
        g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
        if (msg.accept) {
          if (!g.acceptedIds.includes(clientId)) g.acceptedIds.push(clientId);
          g.names[clientId] = name;
        }
        changed = true;
        break;
      }
      case 'gameBegin': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'inviting' || clientId !== g.hostId || g.acceptedIds.length < 2) break;
        g.order = shuffleArray(g.acceptedIds);
        g.invitedIds = [];
        g.round = 1;
        g.turnIndex = 0;
        g.scores = {};
        for (const id of g.order) g.scores[id] = 0;
        beginTurn(room2);
        changed = true;
        break;
      }
      case 'gameChooseWord': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'choosing' || clientId !== g.currentDrawerId) break;
        const choices = room2.pendingWordChoices;
        if (!choices || !choices.includes(msg.word)) break;
        room2.pendingWordChoices = null;
        room2.secretWord = msg.word;
        g.wordLength = String(msg.word).length;
        g.turnStartedAt = Date.now();
        g.phase = 'drawing';
        clearTimeout(room2.turnTimer);
        room2.turnTimer = setTimeout(() => { advanceTurn(room2, null); broadcastState(room); }, 60000);
        changed = true;
        break;
      }
      case 'gameStroke': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'drawing' || clientId !== g.currentDrawerId) break;
        const payload = JSON.stringify({ type: 'gameStroke', points: msg.points, color: msg.color, width: msg.width, newStroke: !!msg.newStroke });
        for (const [cid, c] of room2.clients) {
          if (cid !== clientId && g.acceptedIds.includes(cid) && c.ws.readyState === c.ws.OPEN) c.ws.send(payload);
        }
        break;
      }
      case 'gameClearCanvas': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'drawing' || clientId !== g.currentDrawerId) break;
        const payload = JSON.stringify({ type: 'gameClearCanvas' });
        for (const [cid, c] of room2.clients) {
          if (cid !== clientId && g.acceptedIds.includes(cid) && c.ws.readyState === c.ws.OPEN) c.ws.send(payload);
        }
        break;
      }
      case 'gameGuessed': {
        changed = false;
        const g = s.drawGame;
        if (g.phase !== 'drawing' || clientId !== g.currentDrawerId) break;
        if (!g.acceptedIds.includes(msg.guesserId) || msg.guesserId === clientId) break;
        clearTimeout(room2.turnTimer);
        advanceTurn(room2, msg.guesserId);
        changed = true;
        break;
      }
      case 'gameCancel': {
        changed = false;
        const g = s.drawGame;
        if (g.phase === 'idle' || clientId !== g.hostId) break;
        clearTimeout(room2.turnTimer);
        room2.pendingWordChoices = null;
        room2.secretWord = null;
        s.drawGame = defaultDrawGameState();
        changed = true;
        break;
      }
      // ---------------- forca (jogo de adivinhar palavra em grupo) ----------------
      case 'hangmanInvite': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase !== 'idle') break;
        const invited = Array.isArray(msg.to) ? msg.to.filter((id) => room2.clients.has(id) && id !== clientId).slice(0, 49) : [];
        if (!invited.length) break;
        h.phase = 'inviting';
        h.hostId = clientId;
        h.invitedIds = invited;
        h.acceptedIds = [clientId];
        h.names[clientId] = name;
        changed = true;
        break;
      }
      case 'hangmanRespond': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase !== 'inviting' || !h.invitedIds.includes(clientId)) break;
        h.invitedIds = h.invitedIds.filter((id) => id !== clientId);
        if (msg.accept) {
          if (!h.acceptedIds.includes(clientId)) h.acceptedIds.push(clientId);
          h.names[clientId] = name;
        }
        changed = true;
        break;
      }
      case 'hangmanBegin': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase !== 'inviting' || clientId !== h.hostId || h.acceptedIds.length < 2) break;
        h.order = shuffleArray(h.acceptedIds);
        h.invitedIds = [];
        h.round = 1;
        h.turnIndex = 0;
        h.scores = {};
        for (const id of h.order) h.scores[id] = 0;
        beginHangmanTurn(room2);
        changed = true;
        break;
      }
      // Quem tá com a vez digita a palavra secreta — só o servidor guarda o texto de verdade,
      // todo mundo mais só recebe o tamanho dela (igual as 3 palavras do Draw Game).
      case 'hangmanSetWord': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase !== 'setting' || clientId !== h.currentSetterId) break;
        const word = String(msg.word || '').trim();
        if (!/^[a-zA-ZÀ-ÿ]{3,20}$/.test(word)) break;
        room2.hangmanSecretWord = word;
        h.wordLength = word.length;
        h.revealedPattern = [...word].map(() => null);
        h.turnStartedAt = Date.now();
        h.phase = 'playing';
        clearTimeout(room2.hangmanTimer);
        room2.hangmanTimer = setTimeout(() => { finishHangmanRound(room2, room, false); }, 90000);
        changed = true;
        break;
      }
      case 'hangmanGuessLetter': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase !== 'playing' || clientId === h.currentSetterId) break;
        const letter = normalizeLetter(msg.letter).slice(0, 1);
        if (!letter || !/^[a-z]$/.test(letter) || h.guessedLetters.includes(letter)) break;
        h.guessedLetters.push(letter);
        const secretWord = room2.hangmanSecretWord || '';
        const normalizedWord = normalizeLetter(secretWord);
        if (normalizedWord.includes(letter)) {
          h.scores[clientId] = (h.scores[clientId] || 0) + 15;
          // recalcula do zero (mais simples e sem risco de ficar dessincronizado) quais
          // posições já têm letra confirmada — é isso que o cliente usa pra desenhar a palavra
          h.revealedPattern = [...secretWord].map((ch) => (h.guessedLetters.includes(normalizeLetter(ch)) ? ch : null));
          const allGuessed = [...normalizedWord].every((c) => h.guessedLetters.includes(c));
          if (allGuessed) { finishHangmanRound(room2, room, true); break; }
        } else {
          h.wrongLetters.push(letter);
          if (h.wrongLetters.length >= HANGMAN_MAX_WRONG) { finishHangmanRound(room2, room, false); break; }
        }
        changed = true;
        break;
      }
      case 'hangmanCancel': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase === 'idle' || clientId !== h.hostId) break;
        clearTimeout(room2.hangmanTimer);
        clearTimeout(room2.hangmanRoundEndTimer);
        room2.hangmanSecretWord = null;
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
      default:
        changed = false;
    }

    if (changed) broadcastState(room);
  });

  ws.on('close', () => {
    const r2 = rooms.get(room);
    if (!r2) return;
    // Esse close pode ser de um socket VELHO (já substituído por uma reconexão mais nova do
    // mesmo clientId) chegando atrasado — se a entrada atual do Map já não é mais ESTE
    // socket, a pessoa continua na sala de verdade pela conexão nova. Não faz nenhuma faxina
    // de "saiu da sala" nesse caso (senão ela seria tirada do jogo/tela compartilhada à toa).
    if (r2.clients.get(clientId)?.ws !== ws) return;
    r2.clients.delete(clientId);
    if (r2.state.screenSharerId === clientId) {
      // quem tava compartilhando a tela caiu/saiu — libera o campo pra outra pessoa poder compartilhar
      r2.state.screenSharerId = null;
      r2.state.screenSharerName = null;
      broadcastState(room);
    }
    const g = r2.state.drawGame;
    if (g.phase !== 'idle' && (g.acceptedIds.includes(clientId) || g.invitedIds.includes(clientId))) {
      g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
      g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
      if (g.phase === 'inviting') {
        // se quem convidou saiu antes de começar, cancela o convite de vez
        if (clientId === g.hostId) r2.state.drawGame = defaultDrawGameState();
      } else if (g.currentDrawerId === clientId) {
        // quem tava desenhando caiu — pula pra próxima pessoa igual a "ninguém acertou"
        clearTimeout(r2.turnTimer);
        advanceTurn(r2, null);
      } else {
        g.order = g.order.filter((id) => id !== clientId);
      }
      broadcastState(room);
    }
    const h = r2.state.hangmanGame;
    if (h.phase !== 'idle' && (h.acceptedIds.includes(clientId) || h.invitedIds.includes(clientId))) {
      h.invitedIds = h.invitedIds.filter((id) => id !== clientId);
      h.acceptedIds = h.acceptedIds.filter((id) => id !== clientId);
      if (h.phase === 'inviting') {
        if (clientId === h.hostId) r2.state.hangmanGame = defaultHangmanState();
        broadcastState(room);
      } else if (h.currentSetterId === clientId) {
        // quem tava com a vez (escolhendo ou já com a palavra escolhida) caiu — fecha a
        // rodada como "ninguém acertou" e passa pra próxima (finishHangmanRound já faz o broadcast).
        clearTimeout(r2.hangmanTimer);
        finishHangmanRound(r2, room, false);
      } else {
        h.order = h.order.filter((id) => id !== clientId);
        broadcastState(room);
      }
    }
    broadcastMembers(room);
    if (r2.clients.size === 0) {
      // limpa a sala da memória depois de ficar vazia por um tempo
      setTimeout(() => {
        if (rooms.get(room)?.clients.size === 0) rooms.delete(room);
      }, 10 * 60 * 1000);
    }
  });
});

// enquanto algo estiver tocando, reforça o estado periodicamente
// (corrige drift e cobre eventuais mensagens perdidas em conexões instáveis)
setInterval(() => {
  for (const [code, room] of rooms) {
    if (room.state.isPlaying) broadcastState(code);
  }
}, 5000);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`🎉 Festa Sync rodando em http://localhost:${PORT}`);
});
