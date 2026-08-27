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

// rooms: Map<codigoDaSala, { state, clients: Map<clientId, {ws, name}> }>
const rooms = new Map();

function defaultRoomState() {
  return { queue: [], currentIndex: -1, isPlaying: false, position: 0, updatedAt: Date.now(), hostName: null };
}

function getRoom(code) {
  if (!rooms.has(code)) rooms.set(code, { state: defaultRoomState(), clients: new Map() });
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
  const members = [...room.clients.values()].map((c) => c.name);
  const payload = JSON.stringify({ type: 'members', members });
  for (const { ws } of room.clients.values()) {
    if (ws.readyState === ws.OPEN) ws.send(payload);
  }
}

function sanitizeRoomCode(raw) {
  return (raw || 'FESTA').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12) || 'FESTA';
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const room = sanitizeRoomCode(url.searchParams.get('room'));
  let name = (url.searchParams.get('name') || 'Convidado').slice(0, 24);
  const clientId = url.searchParams.get('id') || genId();

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
      default:
        changed = false;
    }

    if (changed) broadcastState(room);
  });

  ws.on('close', () => {
    const r2 = rooms.get(room);
    if (!r2) return;
    r2.clients.delete(clientId);
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
