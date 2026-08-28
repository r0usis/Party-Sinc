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
app.use(express.static(path.join(__dirname, 'public')));

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
      default:
        changed = false;
    }

    if (changed) broadcastState(room);
  });

  ws.on('close', () => {
    const r2 = rooms.get(room);
    if (!r2) return;
    r2.clients.delete(clientId);
    if (r2.state.screenSharerId === clientId) {
      // quem tava compartilhando a tela caiu/saiu — libera o campo pra outra pessoa poder compartilhar
      r2.state.screenSharerId = null;
      r2.state.screenSharerName = null;
      broadcastState(room);
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
