// party/worker.js — Festa Sync na Cloudflare (PartyServer), no lugar da hospedagem do
// PartyKit, que parou de aceitar deploy em 16/10/2026 e foi desligada em 23/10/2026.
//
// A lógica toda continua em party/server.js (FestaSyncParty), escrita pra API do PartyKit.
// Aqui é só um adaptador fino: cada sala é um Durable Object "Main" (então os endereços
// /parties/main/<sala> continuam os mesmos pro site e pro app) e ele entrega pro
// FestaSyncParty um objeto `room` com a mesma cara do Party.Room do PartyKit.

import { Server, routePartykitRequest } from 'partyserver';
import FestaSyncParty from './server.js';

export class Main extends Server {
  constructor(ctx, env) {
    super(ctx, env);
    const self = this;
    const room = {
      // o PartyServer só sabe o nome depois que a sala é aberta pelo endereço (ou restaurada
      // do storage num alarme) — o server.js já trata o caso de ainda não ter (ver roomCode)
      get id() { return self.name; },
      get storage() { return self.ctx.storage; },
      env,
      getConnections: () => self.getConnections(),
      context: {
        // usado pelo Contexto pra buscar o vocabulário e as posições (public/contexto/)
        assets: { fetch: (path) => env.ASSETS.fetch(new Request(new URL(path, 'https://assets.local'))) },
      },
    };
    this.party = new FestaSyncParty(room);
  }

  onStart() { return this.party.onStart(); }
  onConnect(connection, ctx) { return this.party.onConnect(connection, ctx); }
  // no PartyServer a ordem é (conexão, mensagem); no PartyKit era (mensagem, conexão)
  onMessage(connection, message) { return this.party.onMessage(message, connection); }
  onClose(connection) { return this.party.onClose(connection); }
  onRequest(request) { return this.party.onRequest(request); }
  onAlarm() { return this.party.onAlarm(); }
}

export default {
  async fetch(request, env) {
    // /parties/main/<sala> -> a sala (WebSocket ou HTTP); o resto é o site (public/)
    return (await routePartykitRequest(request, env)) || env.ASSETS.fetch(request);
  },
};
