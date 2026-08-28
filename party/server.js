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
  }

  // Chamado ao (re)acordar a sala — recarrega o que foi salvo antes de hibernar.
  async onStart() {
    const saved = await this.room.storage.get('roomData');
    if (saved) {
      this.created = true;
      this.password = saved.password;
      this.maxPeople = saved.maxPeople;
      this.playback = saved.playback;
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
        connection.close(CLOSE_ROOM_FULL, `Sala cheia (máximo de ${this.maxPeople} pessoas).`);
        return;
      }
    }

    connection.setState({ clientId, name });
    await this.persist();

    connection.send(JSON.stringify({ type: 'state', state: this.playback }));
    this.broadcastMembers();
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
      default:
        changed = false;
    }

    if (changed) {
      await this.persist();
      this.broadcastState();
    }
  }

  async onClose(connection) {
    if (this.playback.screenSharerId === connection.state?.clientId) {
      // quem tava compartilhando a tela caiu/saiu — libera o campo pra outra pessoa poder compartilhar
      this.playback.screenSharerId = null;
      this.playback.screenSharerName = null;
      await this.persist();
      this.broadcastState();
    }
    // não precisa de faxina manual de sala vazia aqui (o server.js original apagava a
    // sala da memória 10min depois de ficar vazia) — o próprio PartyKit já hiberna a
    // sala sozinho quando ninguém está conectado.
    this.broadcastMembers();
  }
}
