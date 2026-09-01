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
    contextoGame: defaultContextoGameState(),
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
    wordTheme: null, // rótulo do tema (ex: "Animais") quando a palavra foi sorteada por tema — null quando foi digitada livremente
  };
}

// Bancos de palavra por tema — usados quando quem tem a vez pede pro app sortear em vez de
// digitar a própria palavra. Só o servidor conhece essas listas.
const HANGMAN_THEMES = {
  animais: ['elefante', 'girafa', 'crocodilo', 'tartaruga', 'borboleta', 'tubarão', 'canguru', 'morcego', 'coruja', 'esquilo', 'hipopótamo', 'camaleão', 'pinguim', 'lagarto', 'avestruz'],
  comidas: ['macarrão', 'feijoada', 'brigadeiro', 'coxinha', 'picanha', 'tapioca', 'moqueca', 'pastel', 'lasanha', 'churrasco', 'sorvete', 'pipoca', 'canjica', 'acarajé', 'vatapá'],
  paises: ['brasil', 'portugal', 'argentina', 'japão', 'alemanha', 'canadá', 'austrália', 'egito', 'méxico', 'marrocos', 'tailândia', 'noruega', 'grécia', 'irlanda', 'turquia'],
  filmes: ['titanic', 'matrix', 'avatar', 'shrek', 'frozen', 'coringa', 'vingadores', 'friends', 'simpsons', 'gladiador', 'moana', 'rocky', 'madagascar', 'aladdin', 'mulan'],
  profissoes: ['bombeiro', 'professor', 'engenheiro', 'veterinário', 'cozinheiro', 'eletricista', 'jornalista', 'advogado', 'piloto', 'dentista', 'marceneiro', 'bibliotecário', 'farmacêutico', 'padeiro', 'fotógrafo'],
  objetos: ['liquidificador', 'despertador', 'lanterna', 'ventilador', 'tesoura', 'martelo', 'fogão', 'geladeira', 'cadeado', 'termômetro', 'binóculo', 'isqueiro', 'abajur', 'escova', 'panela'],
};
const HANGMAN_THEME_LABELS = {
  animais: 'Animais', comidas: 'Comidas', paises: 'Países', filmes: 'Filmes e séries',
  profissoes: 'Profissões', objetos: 'Objetos',
};

// ---------------- roleta de categorias (tipo "Stop"/Adedanha) ----------------
// Mesma arquitetura de convite/turnos do Draw Game e da Forca, com cronômetro de 30s
// controlado pelo SERVIDOR: se ninguém clicar em "próxima letra" a tempo, ele passa a vez sozinho.
const STOP_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
const STOP_ROUND_SECONDS = 30;
function defaultStopGameState() {
  return {
    phase: 'idle', // idle | inviting | playing | finished
    hostId: null,
    invitedIds: [],
    acceptedIds: [],
    order: [],
    turnIndex: 0,
    names: {},
    theme: '',
    usedLetters: [],
    currentLetter: null,
    currentPickerId: null,
    currentPickerName: null,
    roundStartedAt: null,
  };
}

// ---------------- jogo do contexto (adivinha a palavra secreta por "proximidade") ----------------
// Versão de festa, sem IA de embeddings: cada palavra secreta já vem com uma lista de palavras
// relacionadas, da mais próxima pra mais distante, escolhida à mão (ver CONTEXTO_BANK). A
// posição de um palpite nessa lista é a "dica de proximidade"; quem não aparece é "bem distante".
const CONTEXTO_MAX_ROUNDS = 5;
const CONTEXTO_BANK = [
  { word: 'cachorro', related: ['cão', 'gato', 'filhote', 'latido', 'focinho', 'coleira', 'osso', 'canil', 'vira-lata', 'poodle', 'labrador', 'animal', 'mascote', 'rosnado', 'patinha', 'veterinário', 'passeio', 'ração', 'dono', 'fidelidade', 'cheirar', 'rabo', 'brincar', 'adestrar', 'abrigo', 'resgate', 'coleira', 'pet shop', 'brinquedo', 'osso de borracha'] },
  { word: 'praia', related: ['mar', 'areia', 'sol', 'biquíni', 'protetor solar', 'coqueiro', 'onda', 'barraca', 'surf', 'verão', 'calor', 'maiô', 'concha', 'caranguejo', 'guarda-sol', 'canga', 'quiosque', 'nadar', 'oceano', 'litoral', 'bronzeado', 'castelo de areia', 'salva-vidas', 'boia', 'mergulho', 'vento', 'gaivota', 'sombrinha', 'piscina', 'toalha'] },
  { word: 'futebol', related: ['bola', 'gol', 'time', 'jogador', 'campo', 'juiz', 'cartão', 'torcida', 'camisa', 'chuteira', 'copa', 'campeonato', 'técnico', 'estádio', 'pênalti', 'escanteio', 'zagueiro', 'atacante', 'goleiro', 'drible', 'arbitragem', 'uniforme', 'torcedor', 'comemorar', 'seleção', 'liga', 'apito', 'grama', 'arquibancada', 'lance'] },
  { word: 'café', related: ['xícara', 'açúcar', 'leite', 'cafeteria', 'expresso', 'cheiro', 'manhã', 'padaria', 'cafeína', 'grãos', 'coado', 'capuccino', 'pão', 'torrada', 'bar', 'garçom', 'mesa', 'guardanapo', 'colher', 'açucareiro', 'descafeinado', 'moído', 'forte', 'quente', 'aroma', 'pausa', 'trabalho', 'conversa', 'bule', 'coador'] },
  { word: 'escola', related: ['professor', 'aluno', 'caderno', 'lousa', 'mochila', 'recreio', 'uniforme', 'prova', 'boletim', 'diretor', 'merenda', 'matéria', 'quadro', 'giz', 'lição', 'colegas', 'ensino', 'aprender', 'disciplina', 'turma', 'série', 'formatura', 'biblioteca', 'laboratório', 'campainha', 'fila', 'notas', 'estudar', 'sala de aula', 'ônibus escolar'] },
  { word: 'hospital', related: ['médico', 'enfermeira', 'remédio', 'paciente', 'ambulância', 'consulta', 'cirurgia', 'leito', 'plantão', 'receita', 'exame', 'emergência', 'curativo', 'injeção', 'doença', 'tratamento', 'corredor', 'maca', 'uti', 'raio-x', 'alta', 'internação', 'soro', 'sala de espera', 'especialista', 'diagnóstico', 'clínica', 'posto de saúde', 'vacina', 'febre'] },
  { word: 'casamento', related: ['noiva', 'noivo', 'aliança', 'altar', 'buquê', 'festa', 'convidado', 'igreja', 'vestido branco', 'padrinho', 'madrinha', 'valsa', 'lua de mel', 'bolo', 'celebrante', 'cerimônia', 'brinde', 'terno', 'véu', 'damas de honra', 'convite', 'música', 'dança', 'promessa', 'união', 'aniversário de casamento', 'foto', 'recepção', 'doces', 'padre'] },
  { word: 'computador', related: ['teclado', 'mouse', 'tela', 'internet', 'programa', 'arquivo', 'senha', 'notebook', 'processador', 'memória', 'software', 'hardware', 'wifi', 'monitor', 'impressora', 'aplicativo', 'navegador', 'download', 'vírus', 'atualização', 'tecnologia', 'digitar', 'código', 'jogo', 'hd', 'nuvem', 'backup', 'cabo', 'energia', 'pendrive'] },
  { word: 'chuva', related: ['guarda-chuva', 'nuvem', 'trovão', 'relâmpago', 'poça', 'molhado', 'temporal', 'enchente', 'gota', 'vento', 'tempestade', 'capa de chuva', 'raio', 'granizo', 'umidade', 'inverno', 'arco-íris', 'garoa', 'dilúvio', 'sombrinha', 'telhado', 'alagamento', 'previsão', 'nublado', 'respingo', 'calçada', 'córrego', 'enxurrada', 'trovoada', 'pinga na telha'] },
  { word: 'natal', related: ['papai noel', 'árvore', 'presente', 'ceia', 'luzes', 'panetone', 'renas', 'sino', 'família', 'meia', 'estrela', 'guirlanda', 'celebração', 'missa do galo', 'enfeites', 'música natalina', 'amigo secreto', 'peru', 'boas festas', 'dezembro', 'presépio', 'trenó', 'gorro vermelho', 'bola de natal', 'confraternização', 'réveillon', 'fogos', 'festa', 'neve', 'cartão de natal'] },
  { word: 'aniversário', related: ['bolo', 'vela', 'parabéns', 'festa', 'convidado', 'presente', 'balão', 'docinho', 'salgadinho', 'aniversariante', 'idade', 'comemoração', 'decoração', 'música', 'brigadeiro', 'convite', 'mesa de doces', 'palhaço', 'cartão', 'surpresa', 'abraço', 'foto', 'bexiga', 'confete', 'chapéuzinho', 'bandeirinha', 'refrigerante', 'celebração', 'amigo', 'lembrancinha'] },
  { word: 'churrasco', related: ['carvão', 'carne', 'churrasqueira', 'espeto', 'cerveja', 'picanha', 'linguiça', 'sal grosso', 'fumaça', 'brasa', 'farofa', 'vinagrete', 'pão de alho', 'churrasqueiro', 'domingo', 'quintal', 'amigos', 'geladinha', 'grelha', 'costela', 'frango', 'assar', 'tempero', 'faca', 'cortar', 'apimentado', 'feijão tropeiro', 'mandioca', 'refrigerante', 'família'] },
  { word: 'viagem', related: ['mala', 'aeroporto', 'passagem', 'hotel', 'passaporte', 'turismo', 'destino', 'avião', 'roteiro', 'bagagem', 'souvenir', 'câmbio', 'mapa', 'guia turístico', 'mochila', 'feriado', 'aventura', 'hospedagem', 'check-in', 'embarque', 'excursão', 'estrada', 'rodoviária', 'gps', 'seguro viagem', 'cidade nova', 'fuso horário', 'cultura', 'foto de viagem', 'trem'] },
  { word: 'cinema', related: ['pipoca', 'ingresso', 'sessão', 'filme', 'tela grande', 'poltrona', 'trailer', 'ator', 'diretor', 'bilheteria', 'refrigerante', '3d', 'lançamento', 'sala escura', 'crítica', 'roteiro', 'elenco', 'legenda', 'dublagem', 'franquia', 'fila', 'sessão da tarde', 'blockbuster', 'oscar', 'streaming', 'cartaz', 'estreia', 'pipoqueiro', 'combo', 'namorados'] },
  { word: 'academia', related: ['musculação', 'halter', 'esteira', 'personal trainer', 'treino', 'suor', 'peso', 'abdômen', 'corrida', 'exercício', 'aquecimento', 'alongamento', 'proteína', 'whey', 'série', 'repetição', 'cardio', 'espelho', 'spinning', 'aparelho', 'avaliação física', 'hipertrofia', 'dor muscular', 'matrícula', 'vestiário', 'toalha', 'garrafinha', 'meta', 'disciplina', 'resultado'] },
  { word: 'cozinha', related: ['fogão', 'panela', 'geladeira', 'receita', 'tempero', 'faca', 'tábua', 'forno', 'liquidificador', 'ingrediente', 'cheiro', 'prato', 'colher de pau', 'avental', 'chef', 'assar', 'refogar', 'louça', 'pia', 'armário', 'micro-ondas', 'especiaria', 'sabor', 'cozinhar', 'jantar', 'almoço', 'cardápio', 'utensílio', 'panela de pressão', 'churrasqueira'] },
];
function normalizeWord(s) {
  return String(s || '').normalize('NFD').replace(DIACRITICS_RE, '').toLowerCase().trim();
}
function defaultContextoGameState() {
  return {
    phase: 'idle', // idle | inviting | playing | roundEnd | finished
    hostId: null,
    invitedIds: [],
    acceptedIds: [],
    order: [],
    round: 0,
    names: {},
    scores: {},
    guesses: [], // tentativas da rodada atual, ordenadas da mais perto pra mais longe
    lastRoundResult: null, // { word, winnerId, winnerName, points, guessCount }
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
    this.hangmanUsedThemeWords = new Set(); // palavras já sorteadas por tema nessa partida (evita repetir)
    this.stopTimer = null; // cronômetro dos 30s da letra da vez no Stop
    this.contextoSecretIndex = null; // índice no CONTEXTO_BANK da palavra secreta da rodada atual
    this.contextoUsed = new Set(); // índices do banco já sorteados nessa partida (evita repetir)
    this.contextoRoundEndTimer = null; // folga pra mostrar quem ganhou antes de trocar de rodada
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
    // Tudo dentro de um try/finally: uma sala com MUITA conexão pendurada (já vimos passar de
    // 200) é exatamente o cenário mais provável de algo dar errado de um jeito que eu não
    // previ no meio da faxina — e se isso matasse o ciclo sem reagendar o próximo alarme, a
    // sala ficaria travada PRA SEMPRE (o próximo ciclo é a única coisa que pode consertar
    // ela, já que não depende de ninguém conseguir entrar). Aconteça o que acontecer aqui
    // dentro, o próximo ciclo sempre é reagendado.
    let connections = [];
    try {
      // Se sobrou mais de uma conexão viva pro mesmo clientId (reconexão rápida demais, duas
      // abas da mesma pessoa brigando pela mesma vaga, etc.), fecha todas menos a mais recente
      // — não depende de achar a causa exata, só garante que isso nunca fica se acumulando
      // pra sempre (a exibição já se protege sozinha, ver uniqueLiveConnections, mas aqui é
      // quem realmente libera a vaga/recurso de verdade).
      const byClientId = new Map();
      for (const c of this.room.getConnections()) {
        const cid = c.state?.clientId;
        if (!cid) continue;
        if (!byClientId.has(cid)) byClientId.set(cid, []);
        byClientId.get(cid).push(c);
      }
      for (const conns of byClientId.values()) {
        if (conns.length > 1) {
          for (const c of conns.slice(0, -1)) { try { c.close(); } catch (e) {} this.pendingPings.delete(c.id); }
        }
      }
      connections = [...this.room.getConnections()];
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
    } catch (e) {
      // segue pro finally de qualquer jeito — o próximo ciclo tenta de novo
    } finally {
      let hasConnections = connections.length > 0;
      if (!hasConnections) {
        try { hasConnections = [...this.room.getConnections()].length > 0; } catch (e) { hasConnections = false; }
      }
      if (hasConnections) {
        try { await this.room.storage.setAlarm(Date.now() + 20000); } catch (e) { /* o próximo evento que acordar a sala tenta de novo */ }
      }
    }
  }

  async onAlarm() {
    // Mesma ideia: nunca deixa o alarme "morrer" por causa de um erro inesperado — checkHeartbeat
    // já se protege sozinho (try/finally lá dentro), isso aqui é só um cinto de segurança extra.
    try { await this.checkHeartbeat(); } catch (e) { /* checkHeartbeat já tentou reagendar sozinho */ }
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
    // menos de 2 gente não dá pra jogar (precisa de quem desenha + quem adivinha)
    if (g.round > 3 || g.order.length < 2) {
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

  // Tira alguém do Draw Game por vontade própria (botão "Sair") — mesma lógica que já rodava
  // só na desconexão (onClose), reaproveitada dos dois lugares.
  applyDrawGameLeave(clientId) {
    const g = this.playback.drawGame;
    if (g.phase === 'idle') return false;
    if (!g.acceptedIds.includes(clientId) && !g.invitedIds.includes(clientId)) return false;
    g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
    g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
    if (g.phase === 'inviting') {
      if (clientId === g.hostId) this.playback.drawGame = defaultDrawGameState();
    } else if (g.currentDrawerId === clientId) {
      clearTimeout(this.turnTimer);
      this.advanceTurn(null);
    } else {
      g.order = g.order.filter((id) => id !== clientId);
      if (g.order.length < 2 && (g.phase === 'choosing' || g.phase === 'drawing')) {
        clearTimeout(this.turnTimer);
        g.phase = 'finished';
        g.currentDrawerId = null;
        g.currentDrawerName = null;
      }
    }
    return true;
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
    // menos de 2 gente não dá pra jogar (precisa de quem escolhe a palavra + quem adivinha)
    if (g.round > 3 || g.order.length < 2) {
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
    g.wordTheme = null;
    this.hangmanSecretWord = null;
  }

  // Tira alguém da Forca por vontade própria (botão "Sair") — mesma ideia do applyDrawGameLeave.
  applyHangmanLeave(clientId) {
    const h = this.playback.hangmanGame;
    if (h.phase === 'idle') return false;
    if (!h.acceptedIds.includes(clientId) && !h.invitedIds.includes(clientId)) return false;
    h.invitedIds = h.invitedIds.filter((id) => id !== clientId);
    h.acceptedIds = h.acceptedIds.filter((id) => id !== clientId);
    if (h.phase === 'inviting') {
      if (clientId === h.hostId) this.playback.hangmanGame = defaultHangmanState();
    } else if (h.currentSetterId === clientId) {
      clearTimeout(this.hangmanTimer);
      this.finishHangmanRound(false); // já persiste e faz o broadcast sozinho
    } else {
      h.order = h.order.filter((id) => id !== clientId);
      if (h.order.length < 2 && (h.phase === 'setting' || h.phase === 'playing')) {
        clearTimeout(this.hangmanTimer);
        h.phase = 'finished';
        h.currentSetterId = null;
        h.currentSetterName = null;
      }
    }
    return true;
  }

  // Pula gente que já não está mais conectada (saiu no meio do jogo).
  skipDisconnectedStopPlayers() {
    const g = this.playback.stopGame;
    const connectedIds = new Set([...this.room.getConnections()].map((c) => c.state?.clientId));
    while (g.order.length && !connectedIds.has(g.order[g.turnIndex])) {
      g.order.splice(g.turnIndex, 1);
      if (g.turnIndex >= g.order.length) g.turnIndex = 0;
    }
  }
  beginStopTurn() {
    const g = this.playback.stopGame;
    this.skipDisconnectedStopPlayers();
    clearTimeout(this.stopTimer);
    if (g.order.length < 2 || g.usedLetters.length >= STOP_ALPHABET.length) {
      g.phase = g.order.length ? 'finished' : 'idle';
      g.currentPickerId = null;
      g.currentPickerName = null;
      return;
    }
    g.currentPickerId = g.order[g.turnIndex];
    g.currentPickerName = g.names[g.currentPickerId] || 'Alguém';
    g.currentLetter = null;
    g.roundStartedAt = null;
  }
  advanceStopTurn() {
    const g = this.playback.stopGame;
    g.turnIndex++;
    if (g.turnIndex >= g.order.length) g.turnIndex = 0;
    this.beginStopTurn();
  }
  // Tira alguém do Stop por vontade própria (botão "Sair").
  applyStopLeave(clientId) {
    const g = this.playback.stopGame;
    if (g.phase === 'idle') return false;
    if (!g.acceptedIds.includes(clientId) && !g.invitedIds.includes(clientId)) return false;
    g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
    g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
    if (g.phase === 'inviting') {
      if (clientId === g.hostId) this.playback.stopGame = defaultStopGameState();
    } else if (g.currentPickerId === clientId) {
      clearTimeout(this.stopTimer);
      this.advanceStopTurn();
    } else {
      g.order = g.order.filter((id) => id !== clientId);
      if (g.order.length < 2) {
        clearTimeout(this.stopTimer);
        g.phase = g.order.length ? 'finished' : 'idle';
        g.currentPickerId = null;
        g.currentPickerName = null;
      }
    }
    return true;
  }

  beginContextoTurn() {
    const g = this.playback.contextoGame;
    const connectedIds = new Set([...this.room.getConnections()].map((c) => c.state?.clientId));
    g.order = g.order.filter((id) => connectedIds.has(id));
    g.acceptedIds = g.acceptedIds.filter((id) => connectedIds.has(id));
    if (g.round >= CONTEXTO_MAX_ROUNDS || g.acceptedIds.length < 2) {
      g.phase = g.acceptedIds.length ? 'finished' : 'idle';
      return;
    }
    const available = CONTEXTO_BANK.map((_, i) => i).filter((i) => !this.contextoUsed.has(i));
    const pool = available.length ? available : CONTEXTO_BANK.map((_, i) => i);
    this.contextoSecretIndex = pool[Math.floor(Math.random() * pool.length)];
    g.round++;
    g.guesses = [];
    g.lastRoundResult = null;
    g.phase = 'playing';
  }
  finishContextoRound(winnerId) {
    const g = this.playback.contextoGame;
    const entry = CONTEXTO_BANK[this.contextoSecretIndex];
    const guessCount = g.guesses.length;
    const points = Math.max(15, Math.round(100 - (guessCount - 1) * 4));
    g.scores[winnerId] = (g.scores[winnerId] || 0) + points;
    g.lastRoundResult = { word: entry.word, winnerId, winnerName: g.names[winnerId] || 'Alguém', points, guessCount };
    g.phase = 'roundEnd';
    this.contextoUsed.add(this.contextoSecretIndex);
    clearTimeout(this.contextoRoundEndTimer);
    this.contextoRoundEndTimer = setTimeout(async () => {
      this.beginContextoTurn();
      await this.persist();
      this.broadcastState();
    }, 4000);
    this.persist();
    this.broadcastState();
  }
  // Tira alguém do Contexto por vontade própria (botão "Sair") — aqui não tem "vez" de
  // ninguém (todo mundo tenta em paralelo), então é só sair da lista mesmo.
  applyContextoLeave(clientId) {
    const g = this.playback.contextoGame;
    if (g.phase === 'idle') return false;
    if (!g.acceptedIds.includes(clientId) && !g.invitedIds.includes(clientId)) return false;
    g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
    g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
    g.order = g.order.filter((id) => id !== clientId);
    if (g.phase === 'inviting') {
      if (clientId === g.hostId) this.playback.contextoGame = defaultContextoGameState();
    } else if (g.acceptedIds.length < 2) {
      clearTimeout(this.contextoRoundEndTimer);
      g.phase = g.acceptedIds.length ? 'finished' : 'idle';
    }
    return true;
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
      // Sala salva de ANTES do Stop virar por turnos (ou de ANTES do Contexto existir) tem um
      // formato velho (ou nem tem o campo) — sem essa checagem, "stopGame.phase" fica undefined
      // pra sempre nela e qualquer mensagem do jogo (stopPickLetter etc.) quebra por dentro.
      if (!this.playback.stopGame || typeof this.playback.stopGame.phase === 'undefined') {
        this.playback.stopGame = defaultStopGameState();
      }
      if (!this.playback.contextoGame) this.playback.contextoGame = defaultContextoGameState();
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

  // Agrupa as conexões vivas por clientId — se por qualquer motivo mais de uma conexão
  // acabou existindo pro mesmo clientId ao mesmo tempo (reconexão rápida demais, duas ABAS da
  // mesma pessoa brigando pela mesma vaga, etc.), considera só uma "de verdade" por pessoa.
  // Não depende de achar a causa exata da duplicata — a lista de membros e a contagem de vaga
  // NUNCA devem mostrar/contar a mesma pessoa duas vezes, não importa quantas conexões cruas
  // fiquem penduradas por trás (essas o batimento cardíaco cuida de fechar, ver checkHeartbeat).
  uniqueLiveConnections() {
    const byClientId = new Map();
    for (const c of this.room.getConnections()) {
      const cid = c.state?.clientId;
      if (!cid) continue;
      byClientId.set(cid, c); // set com a mesma key sobrescreve — fica com a última encontrada
    }
    return [...byClientId.values()];
  }

  broadcastMembers() {
    // clientId vai junto (não só o nome) — é o que o chat de voz usa pra saber com quem
    // abrir uma conexão WebRTC (nomes podem repetir entre pessoas, clientId não).
    const members = this.uniqueLiveConnections().map((c) => ({ clientId: c.state?.clientId, name: c.state?.name || 'Convidado' }));
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
      // Conta VAGA por pessoa única (clientId), não por conexão crua — sem isso, uma sala com
      // um monte de conexão fantasma duplicada da MESMA pessoa (ver uniqueLiveConnections)
      // aparecia "lotada" mesmo tendo só 2 ou 3 pessoas de verdade nela.
      const uniqueOtherPeople = new Set(others.map((c) => c.state?.clientId).filter(Boolean)).size;
      if (!isReconnect && uniqueOtherPeople + 1 > this.maxPeople) {
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
        // imagem vem como data URL (já comprimida no client); trava de segurança de
        // tamanho aqui também, pra não confiar só no que o navegador de quem manda promete.
        let image = typeof msg.image === 'string' ? msg.image : null;
        if (image && (!image.startsWith('data:image/') || image.length > 500_000)) image = null;
        if (!text && !image) { changed = false; break; }
        s.chatLog.push({ id: genId(), clientId: sender.state?.clientId, name, text, image: image || undefined, ts: Date.now() });
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
      case 'gameLeave': {
        changed = this.applyDrawGameLeave(sender.state?.clientId);
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
        this.hangmanUsedThemeWords = new Set();
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
      // Alternativa ao digitar a palavra: quem tem a vez escolhe um TEMA e o app sorteia a
      // palavra sozinho desse banco (sem repetir palavra já usada nessa partida, se der).
      case 'hangmanPickTheme': {
        changed = false;
        const h = s.hangmanGame;
        const myId = sender.state?.clientId;
        if (h.phase !== 'setting' || myId !== h.currentSetterId) break;
        const themeKey = String(msg.theme || '');
        const bank = HANGMAN_THEMES[themeKey];
        if (!bank) break;
        const options = bank.filter((w) => !this.hangmanUsedThemeWords.has(w));
        const pool = options.length ? options : bank;
        const word = pool[Math.floor(Math.random() * pool.length)];
        this.hangmanUsedThemeWords.add(word);
        this.hangmanSecretWord = word;
        h.wordLength = word.length;
        h.wordTheme = HANGMAN_THEME_LABELS[themeKey] || themeKey;
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
      case 'hangmanLeave': {
        changed = this.applyHangmanLeave(sender.state?.clientId);
        break;
      }
      // ---------------- roleta de categorias (agora por turnos, com cronômetro do servidor) ----------------
      case 'stopInvite': {
        changed = false;
        const g = s.stopGame;
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
      case 'stopRespond': {
        changed = false;
        const g = s.stopGame;
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
      case 'stopBegin': {
        changed = false;
        const g = s.stopGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'inviting' || myId !== g.hostId || g.acceptedIds.length < 2) break;
        g.order = shuffleArray(g.acceptedIds);
        g.invitedIds = [];
        g.turnIndex = 0;
        g.usedLetters = [];
        g.phase = 'playing';
        this.beginStopTurn();
        changed = true;
        break;
      }
      case 'stopSetTheme': {
        changed = false;
        const g = s.stopGame;
        if (g.phase !== 'playing') break;
        g.theme = String(msg.theme || '').trim().slice(0, 60);
        changed = true;
        break;
      }
      // Só quem tá com a vez sorteia a letra — e só se ainda não tiver uma letra em jogo.
      case 'stopPickLetter': {
        changed = false;
        const g = s.stopGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'playing' || myId !== g.currentPickerId || g.currentLetter) break;
        const letter = String(msg.letter || '').toUpperCase().slice(0, 1);
        if (!/^[A-Z]$/.test(letter) || g.usedLetters.includes(letter)) break;
        g.usedLetters.push(letter);
        g.currentLetter = letter;
        g.roundStartedAt = Date.now();
        clearTimeout(this.stopTimer);
        this.stopTimer = setTimeout(async () => {
          this.advanceStopTurn();
          await this.persist();
          this.broadcastState();
        }, STOP_ROUND_SECONDS * 1000);
        changed = true;
        break;
      }
      // Quem tá com a vez pode passar a bola antes do tempo acabar; o cronômetro de verdade
      // (que decide sozinho se ninguém clicar) fica no servidor.
      case 'stopNextLetter': {
        changed = false;
        const g = s.stopGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'playing' || myId !== g.currentPickerId || !g.currentLetter) break;
        clearTimeout(this.stopTimer);
        this.advanceStopTurn();
        changed = true;
        break;
      }
      case 'stopLeave': {
        changed = this.applyStopLeave(sender.state?.clientId);
        break;
      }
      case 'stopCancel': {
        changed = false;
        const g = s.stopGame;
        const myId = sender.state?.clientId;
        if (g.phase === 'idle') break;
        if (g.phase === 'inviting' && myId !== g.hostId) break;
        clearTimeout(this.stopTimer);
        s.stopGame = defaultStopGameState();
        changed = true;
        break;
      }
      // ---------------- jogo do contexto (adivinha a palavra secreta por "proximidade") ----------------
      case 'contextoInvite': {
        changed = false;
        const g = s.contextoGame;
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
      case 'contextoRespond': {
        changed = false;
        const g = s.contextoGame;
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
      case 'contextoBegin': {
        changed = false;
        const g = s.contextoGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'inviting' || myId !== g.hostId || g.acceptedIds.length < 2) break;
        g.order = [...g.acceptedIds];
        g.invitedIds = [];
        g.round = 0;
        g.scores = {};
        for (const id of g.acceptedIds) g.scores[id] = 0;
        this.contextoUsed = new Set();
        this.beginContextoTurn();
        changed = true;
        break;
      }
      case 'contextoGuess': {
        changed = false;
        const g = s.contextoGame;
        const myId = sender.state?.clientId;
        if (g.phase !== 'playing' || !g.acceptedIds.includes(myId)) break;
        const raw = String(msg.word || '').trim().slice(0, 40);
        if (!raw) break;
        const norm = normalizeWord(raw);
        if (!norm || g.guesses.some((x) => x.norm === norm)) break;
        const entry = CONTEXTO_BANK[this.contextoSecretIndex];
        const secretNorm = normalizeWord(entry.word);
        let rank;
        if (norm === secretNorm) {
          rank = 0;
        } else {
          const idx = entry.related.findIndex((w) => normalizeWord(w) === norm);
          rank = idx >= 0 ? idx + 1 : null;
        }
        g.guesses.push({ word: raw, norm, rank, byId: myId, byName: name, ts: Date.now() });
        g.guesses.sort((a, b) => {
          if (a.rank === null && b.rank === null) return a.ts - b.ts;
          if (a.rank === null) return 1;
          if (b.rank === null) return -1;
          return a.rank - b.rank;
        });
        if (g.guesses.length > 200) g.guesses.length = 200;
        if (rank === 0) { this.finishContextoRound(myId); break; }
        changed = true;
        break;
      }
      case 'contextoLeave': {
        changed = this.applyContextoLeave(sender.state?.clientId);
        break;
      }
      case 'contextoCancel': {
        changed = false;
        const g = s.contextoGame;
        const myId = sender.state?.clientId;
        if (g.phase === 'idle') break;
        if (g.phase === 'inviting' && myId !== g.hostId) break;
        clearTimeout(this.contextoRoundEndTimer);
        s.contextoGame = defaultContextoGameState();
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
    // Games: mesma faxina que o botão "Sair" faria — reaproveita os métodos applyXLeave.
    // finishHangmanRound já persiste e faz o próprio broadcast por dentro; chamar de novo
    // aqui não quebra nada, só manda o mesmo estado uma vez a mais.
    if (this.applyDrawGameLeave(clientId)) { await this.persist(); this.broadcastState(); }
    if (this.applyHangmanLeave(clientId)) { await this.persist(); this.broadcastState(); }
    if (this.applyStopLeave(clientId)) { await this.persist(); this.broadcastState(); }
    if (this.applyContextoLeave(clientId)) { await this.persist(); this.broadcastState(); }
    // não precisa de faxina manual de sala vazia aqui (o server.js original apagava a
    // sala da memória 10min depois de ficar vazia) — o próprio PartyKit já hiberna a
    // sala sozinho quando ninguém está conectado.
    this.broadcastMembers();
  }
}