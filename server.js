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
const CLOSE_KICKED = 4006; // alguém da sala expulsou essa pessoa (4005 já é usado internamente pro dedup de reconexão)

function defaultRoomState() {
  return {
    queue: [], currentIndex: -1, isPlaying: false, position: 0, updatedAt: Date.now(), hostName: null,
    screenSharerId: null, screenSharerName: null, // quem está compartilhando a tela agora (só uma pessoa por vez)
    drawGame: defaultDrawGameState(),
    hangmanGame: defaultHangmanState(),
    stopGame: defaultStopGameState(),
    contextoGame: defaultContextoGameState(),
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
    wordTheme: null, // rótulo do tema (ex: "Animais") quando a palavra foi sorteada por tema — null quando foi digitada livremente
  };
}

// Bancos de palavra por tema — usados quando quem tem a vez pede pro app sortear em vez de
// digitar a própria palavra. Só o servidor conhece essas listas (o cliente só sabe o NOME de
// cada tema, pra montar os botões) — assim ninguém descobre a palavra secreta olhando o
// código-fonte que roda no navegador.
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
// Mesma arquitetura de convite/turnos do Draw Game e da Forca: o anfitrião chama gente, todo
// mundo que topa entra numa ordem sorteada, e só quem tá com a vez sorteia a letra da rodada —
// com um cronômetro de 30s controlado pelo SERVIDOR (não só decorativo no cliente): se ninguém
// clicar em "próxima letra" a tempo, o servidor passa a vez sozinho. O tema continua livre (a
// galera decide e digita) e ninguém precisa DIGITAR a resposta (é falado por voz).
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
    theme: '', // tema livre, decidido por quem tiver jogando
    usedLetters: [], // letras (A-Z) já sorteadas
    currentLetter: null, // letra da vez agora (null = ninguém escolheu ainda)
    currentPickerId: null, // quem tem a vez de sortear a letra agora
    currentPickerName: null,
    roundStartedAt: null, // quando essa letra começou a valer — dá pro cronômetro ser igual pra todo mundo
  };
}
// Pula gente que já não está mais conectada (saiu no meio do jogo).
function skipDisconnectedStopPlayers(room2) {
  const g = room2.state.stopGame;
  while (g.order.length && !room2.clients.has(g.order[g.turnIndex])) {
    g.order.splice(g.turnIndex, 1);
    if (g.turnIndex >= g.order.length) g.turnIndex = 0;
  }
}
function beginStopTurn(room2) {
  const g = room2.state.stopGame;
  skipDisconnectedStopPlayers(room2);
  clearTimeout(room2.stopTimer);
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
function advanceStopTurn(room2) {
  const g = room2.state.stopGame;
  g.turnIndex++;
  if (g.turnIndex >= g.order.length) g.turnIndex = 0;
  beginStopTurn(room2);
}
// Tira alguém do Stop por vontade própria (botão "Sair") — mesma ideia do applyDrawGameLeave.
function applyStopLeave(room2, room, clientId) {
  const g = room2.state.stopGame;
  if (g.phase === 'idle') return false;
  if (!g.acceptedIds.includes(clientId) && !g.invitedIds.includes(clientId)) return false;
  g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
  g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
  if (g.phase === 'inviting') {
    if (clientId === g.hostId) room2.state.stopGame = defaultStopGameState();
  } else if (g.currentPickerId === clientId) {
    clearTimeout(room2.stopTimer);
    advanceStopTurn(room2);
  } else {
    g.order = g.order.filter((id) => id !== clientId);
    if (g.order.length < 2) {
      clearTimeout(room2.stopTimer);
      g.phase = g.order.length ? 'finished' : 'idle';
      g.currentPickerId = null;
      g.currentPickerName = null;
    }
  }
  return true;
}

// ---------------- jogo do contexto (adivinha a palavra secreta por "proximidade") ----------------
// Versão de festa, sem IA de embeddings: cada palavra secreta do banco já vem com uma lista de
// palavras relacionadas, da mais próxima pra mais distante, escolhida à mão. Cada palpite que
// alguém manda é comparado com essa lista — a posição nela é a "dica de proximidade" (1 =
// bem quente, número maior = mais frio); quem não aparece na lista é só "bem distante". Todo
// mundo vê o quadro de tentativas em tempo real (igual o jogo original), ordenado da mais
// perto pra mais longe — quem acha a palavra exata primeiro ganha a rodada.
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
    guesses: [], // tentativas da rodada atual, ordenadas da mais perto pra mais longe: { word, rank, byId, byName }
    lastRoundResult: null, // { word, winnerId, winnerName, points, guessCount }
  };
}
function beginContextoTurn(room2) {
  const g = room2.state.contextoGame;
  g.order = g.order.filter((id) => room2.clients.has(id));
  g.acceptedIds = g.acceptedIds.filter((id) => room2.clients.has(id));
  if (g.round >= CONTEXTO_MAX_ROUNDS || g.acceptedIds.length < 2) {
    g.phase = g.acceptedIds.length ? 'finished' : 'idle';
    return;
  }
  const available = CONTEXTO_BANK.map((_, i) => i).filter((i) => !room2.contextoUsed.has(i));
  const pool = available.length ? available : CONTEXTO_BANK.map((_, i) => i);
  room2.contextoSecretIndex = pool[Math.floor(Math.random() * pool.length)];
  g.round++;
  g.guesses = [];
  g.lastRoundResult = null;
  g.phase = 'playing';
}
function finishContextoRound(room2, room, winnerId) {
  const g = room2.state.contextoGame;
  const entry = CONTEXTO_BANK[room2.contextoSecretIndex];
  const guessCount = g.guesses.length; // inclui o palpite vencedor
  // menos tentativas = mais pontos (mesma lógica de "quanto mais rápido, mais pontos" dos outros jogos)
  const points = Math.max(15, Math.round(100 - (guessCount - 1) * 4));
  g.scores[winnerId] = (g.scores[winnerId] || 0) + points;
  g.lastRoundResult = { word: entry.word, winnerId, winnerName: g.names[winnerId] || 'Alguém', points, guessCount };
  g.phase = 'roundEnd';
  room2.contextoUsed.add(room2.contextoSecretIndex);
  clearTimeout(room2.contextoRoundEndTimer);
  room2.contextoRoundEndTimer = setTimeout(() => { beginContextoTurn(room2); broadcastState(room); }, 4000);
  broadcastState(room);
}
// Tira alguém do Contexto por vontade própria (botão "Sair") — aqui não tem "vez" de ninguém
// (todo mundo tenta em paralelo), então é só sair da lista mesmo.
function applyContextoLeave(room2, clientId) {
  const g = room2.state.contextoGame;
  if (g.phase === 'idle') return false;
  if (!g.acceptedIds.includes(clientId) && !g.invitedIds.includes(clientId)) return false;
  g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
  g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
  g.order = g.order.filter((id) => id !== clientId);
  if (g.phase === 'inviting') {
    if (clientId === g.hostId) room2.state.contextoGame = defaultContextoGameState();
  } else if (g.acceptedIds.length < 2) {
    clearTimeout(room2.contextoRoundEndTimer);
    g.phase = g.acceptedIds.length ? 'finished' : 'idle';
  }
  return true;
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
  // menos de 2 gente na roda não dá pra jogar (precisa de quem desenha + quem adivinha) —
  // acontece quando alguém sai no meio (ver applyDrawGameLeave) e sobra só uma pessoa.
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

// Tira alguém do Draw Game por vontade própria (botão "Sair") — mesma lógica que já rodava
// só na desconexão (ws.on('close')), agora reaproveitada dos dois lugares. Devolve true se
// mudou alguma coisa (pra saber se vale a pena fazer broadcast).
function applyDrawGameLeave(room2, clientId) {
  const g = room2.state.drawGame;
  if (g.phase === 'idle') return false;
  if (!g.acceptedIds.includes(clientId) && !g.invitedIds.includes(clientId)) return false;
  g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
  g.acceptedIds = g.acceptedIds.filter((id) => id !== clientId);
  if (g.phase === 'inviting') {
    // se quem convidou saiu antes de começar, cancela o convite de vez
    if (clientId === g.hostId) room2.state.drawGame = defaultDrawGameState();
  } else if (g.currentDrawerId === clientId) {
    // quem tava desenhando saiu — pula pra próxima pessoa igual a "ninguém acertou"
    clearTimeout(room2.turnTimer);
    advanceTurn(room2, null);
  } else {
    g.order = g.order.filter((id) => id !== clientId);
    if (g.order.length < 2 && (g.phase === 'choosing' || g.phase === 'drawing')) {
      clearTimeout(room2.turnTimer);
      g.phase = 'finished';
      g.currentDrawerId = null;
      g.currentDrawerName = null;
    }
  }
  return true;
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
  room2.hangmanSecretWord = null;
}

// Tira alguém da Forca por vontade própria (botão "Sair") — mesma ideia do applyDrawGameLeave.
function applyHangmanLeave(room2, room, clientId) {
  const h = room2.state.hangmanGame;
  if (h.phase === 'idle') return false;
  if (!h.acceptedIds.includes(clientId) && !h.invitedIds.includes(clientId)) return false;
  h.invitedIds = h.invitedIds.filter((id) => id !== clientId);
  h.acceptedIds = h.acceptedIds.filter((id) => id !== clientId);
  if (h.phase === 'inviting') {
    if (clientId === h.hostId) room2.state.hangmanGame = defaultHangmanState();
  } else if (h.currentSetterId === clientId) {
    // quem tava com a vez saiu — fecha a rodada como "ninguém acertou" e passa adiante
    clearTimeout(room2.hangmanTimer);
    finishHangmanRound(room2, room, false);
  } else {
    h.order = h.order.filter((id) => id !== clientId);
    if (h.order.length < 2 && (h.phase === 'setting' || h.phase === 'playing')) {
      clearTimeout(room2.hangmanTimer);
      h.phase = 'finished';
      h.currentSetterId = null;
      h.currentSetterName = null;
    }
  }
  return true;
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
  // Detecta conexão morta que nunca chegou a fechar direito (rede caindo sem aviso, aba
  // suspensa, notebook indo dormir...) — sem isso, ela pode ficar ocupando vaga na sala PRA
  // SEMPRE, até lotar de gente "fantasma" e ninguém mais conseguir entrar (foi exatamente
  // isso que aconteceu). O navegador responde esse ping sozinho, automático, sem precisar de
  // nenhum código do lado do cliente — é parte do próprio protocolo do WebSocket.
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

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
      hangmanUsedThemeWords: new Set(), // palavras já sorteadas por tema nessa partida (evita repetir)
      stopTimer: null, // cronômetro dos 30s da letra da vez no Stop, pra passar a vez sozinho
      contextoSecretIndex: null, // índice no CONTEXTO_BANK da palavra secreta da rodada atual — só o servidor sabe
      contextoUsed: new Set(), // índices do banco já sorteados nessa partida (evita repetir)
      contextoRoundEndTimer: null, // folga pra mostrar quem ganhou antes de trocar de rodada
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
      // Sair do jogo sem encerrar pra todo mundo — quem tava desenhando vira "ninguém acertou"
      // e passa a vez; quem só tava na roda simplesmente sai da lista.
      case 'gameLeave': {
        changed = applyDrawGameLeave(room2, clientId);
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
        room2.hangmanUsedThemeWords = new Set();
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
      // Alternativa ao digitar a palavra: quem tem a vez escolhe um TEMA e o app sorteia a
      // palavra sozinho desse banco (sem repetir palavra já usada nessa partida, se der).
      case 'hangmanPickTheme': {
        changed = false;
        const h = s.hangmanGame;
        if (h.phase !== 'setting' || clientId !== h.currentSetterId) break;
        const themeKey = String(msg.theme || '');
        const bank = HANGMAN_THEMES[themeKey];
        if (!bank) break;
        const used = room2.hangmanUsedThemeWords || (room2.hangmanUsedThemeWords = new Set());
        const options = bank.filter((w) => !used.has(w));
        const pool = options.length ? options : bank; // já usou todas nessa partida? deixa repetir
        const word = pool[Math.floor(Math.random() * pool.length)];
        used.add(word);
        room2.hangmanSecretWord = word;
        h.wordLength = word.length;
        h.wordTheme = HANGMAN_THEME_LABELS[themeKey] || themeKey;
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
        if (h.phase === 'idle') break;
        // cancelar um CONVITE ainda é só de quem convidou (é a partida dele) — mas depois que
        // o jogo já começou de verdade, qualquer um pode reiniciar. Serve de "escape" pra
        // quando o jogo trava (ex: o anfitrião caiu da sala e não tem mais como ele mesmo
        // cancelar) — sem isso, o jogo ficava preso pra sempre nesse caso.
        if (h.phase === 'inviting' && clientId !== h.hostId) break;
        clearTimeout(room2.hangmanTimer);
        clearTimeout(room2.hangmanRoundEndTimer);
        room2.hangmanSecretWord = null;
        s.hangmanGame = defaultHangmanState();
        changed = true;
        break;
      }
      case 'hangmanLeave': {
        changed = applyHangmanLeave(room2, room, clientId);
        break;
      }
      // ---------------- roleta de categorias (agora por turnos, com cronômetro do servidor) ----------------
      case 'stopInvite': {
        changed = false;
        const g = s.stopGame;
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
      case 'stopRespond': {
        changed = false;
        const g = s.stopGame;
        if (g.phase !== 'inviting' || !g.invitedIds.includes(clientId)) break;
        g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
        if (msg.accept) {
          if (!g.acceptedIds.includes(clientId)) g.acceptedIds.push(clientId);
          g.names[clientId] = name;
        }
        changed = true;
        break;
      }
      case 'stopBegin': {
        changed = false;
        const g = s.stopGame;
        if (g.phase !== 'inviting' || clientId !== g.hostId || g.acceptedIds.length < 2) break;
        g.order = shuffleArray(g.acceptedIds);
        g.invitedIds = [];
        g.turnIndex = 0;
        g.usedLetters = [];
        g.phase = 'playing';
        beginStopTurn(room2);
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
        if (g.phase !== 'playing' || clientId !== g.currentPickerId || g.currentLetter) break;
        const letter = String(msg.letter || '').toUpperCase().slice(0, 1);
        if (!/^[A-Z]$/.test(letter) || g.usedLetters.includes(letter)) break;
        g.usedLetters.push(letter);
        g.currentLetter = letter;
        g.roundStartedAt = Date.now();
        clearTimeout(room2.stopTimer);
        room2.stopTimer = setTimeout(() => { advanceStopTurn(room2); broadcastState(room); }, STOP_ROUND_SECONDS * 1000);
        changed = true;
        break;
      }
      // Quem tá com a vez pode passar a bola antes do tempo acabar (ex: ninguém lembrou de
      // nada) — o cronômetro de verdade (que decide sozinho se ninguém clicar) fica no servidor.
      case 'stopNextLetter': {
        changed = false;
        const g = s.stopGame;
        if (g.phase !== 'playing' || clientId !== g.currentPickerId || !g.currentLetter) break;
        clearTimeout(room2.stopTimer);
        advanceStopTurn(room2);
        changed = true;
        break;
      }
      case 'stopLeave': {
        changed = applyStopLeave(room2, room, clientId);
        break;
      }
      case 'stopCancel': {
        changed = false;
        const g = s.stopGame;
        if (g.phase === 'idle') break;
        if (g.phase === 'inviting' && clientId !== g.hostId) break;
        clearTimeout(room2.stopTimer);
        s.stopGame = defaultStopGameState();
        changed = true;
        break;
      }
      // ---------------- jogo do contexto (adivinha a palavra secreta por "proximidade") ----------------
      case 'contextoInvite': {
        changed = false;
        const g = s.contextoGame;
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
      case 'contextoRespond': {
        changed = false;
        const g = s.contextoGame;
        if (g.phase !== 'inviting' || !g.invitedIds.includes(clientId)) break;
        g.invitedIds = g.invitedIds.filter((id) => id !== clientId);
        if (msg.accept) {
          if (!g.acceptedIds.includes(clientId)) g.acceptedIds.push(clientId);
          g.names[clientId] = name;
        }
        changed = true;
        break;
      }
      case 'contextoBegin': {
        changed = false;
        const g = s.contextoGame;
        if (g.phase !== 'inviting' || clientId !== g.hostId || g.acceptedIds.length < 2) break;
        g.order = [...g.acceptedIds];
        g.invitedIds = [];
        g.round = 0;
        g.scores = {};
        for (const id of g.acceptedIds) g.scores[id] = 0;
        room2.contextoUsed = new Set();
        beginContextoTurn(room2);
        changed = true;
        break;
      }
      case 'contextoGuess': {
        changed = false;
        const g = s.contextoGame;
        if (g.phase !== 'playing' || !g.acceptedIds.includes(clientId)) break;
        const raw = String(msg.word || '').trim().slice(0, 40);
        if (!raw) break;
        const norm = normalizeWord(raw);
        if (!norm || g.guesses.some((x) => x.norm === norm)) break; // vazio ou já tentaram essa
        const entry = CONTEXTO_BANK[room2.contextoSecretIndex];
        const secretNorm = normalizeWord(entry.word);
        let rank;
        if (norm === secretNorm) {
          rank = 0; // 0 = acertou em cheio
        } else {
          const idx = entry.related.findIndex((w) => normalizeWord(w) === norm);
          rank = idx >= 0 ? idx + 1 : null; // null = "bem distante", não apareceu na lista
        }
        g.guesses.push({ word: raw, norm, rank, byId: clientId, byName: name, ts: Date.now() });
        // mais perto (rank menor) primeiro; quem não bateu com nada fica no fim, na ordem que tentou
        g.guesses.sort((a, b) => {
          if (a.rank === null && b.rank === null) return a.ts - b.ts;
          if (a.rank === null) return 1;
          if (b.rank === null) return -1;
          return a.rank - b.rank;
        });
        if (g.guesses.length > 200) g.guesses.length = 200; // trava de bom senso
        if (rank === 0) { finishContextoRound(room2, room, clientId); break; }
        changed = true;
        break;
      }
      case 'contextoLeave': {
        changed = applyContextoLeave(room2, clientId);
        break;
      }
      case 'contextoCancel': {
        changed = false;
        const g = s.contextoGame;
        if (g.phase === 'idle') break;
        if (g.phase === 'inviting' && clientId !== g.hostId) break;
        clearTimeout(room2.contextoRoundEndTimer);
        s.contextoGame = defaultContextoGameState();
        changed = true;
        break;
      }
      // Qualquer um na sala pode expulsar alguém (não tem "dono" fixo nessa sala, igual o
      // resto do app) — serve principalmente pra tirar conexão fantasma/pessoa desconhecida
      // que ninguém sabe quem é, sem precisar saber o nome de verdade dela.
      case 'kickMember': {
        changed = false;
        const targetId = String(msg.targetId || '');
        if (!targetId || targetId === clientId) break; // não dá pra se auto-expulsar
        const target = room2.clients.get(targetId);
        if (!target) break;
        try { target.ws.close(CLOSE_KICKED, `Você foi removido da sala por ${name}.`); } catch (e) { /* já pode ter caído sozinha */ }
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
    // Games: mesma faxina que o botão "Sair" faria — reaproveita as funções applyXLeave.
    // Algumas delas (finishHangmanRound) já fazem o próprio broadcast por dentro; chamar
    // broadcastState de novo aqui não quebra nada, só manda o mesmo estado uma vez a mais.
    if (applyDrawGameLeave(r2, clientId)) broadcastState(room);
    if (applyHangmanLeave(r2, room, clientId)) broadcastState(room);
    if (applyStopLeave(r2, room, clientId)) broadcastState(room);
    if (applyContextoLeave(r2, clientId)) broadcastState(room);
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

// Ping/pong pra achar conexão morta (ver comentário lá em cima, junto do ws.isAlive) — quem
// não respondeu ao ping do ciclo ANTERIOR até agora é considerado morto de vez e derrubado
// (isso dispara o 'close' dela normalmente, que já faz toda a faxina de "saiu da sala").
setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`🎉 Festa Sync rodando em http://localhost:${PORT}`);
});
