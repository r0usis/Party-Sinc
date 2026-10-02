// ---------------- Contexto: posição de QUALQUER palavra ----------------
// Antes, cada palavra secreta só conhecia as ~30 palavras da lista feita à mão; qualquer outro
// palpite virava "não conheço". Agora tem um vocabulário de 40 mil palavras do português
// (public/contexto/vocabulario.txt, uma por linha, já sem acento e em minúscula) e, pra cada
// palavra secreta, um arquivo public/contexto/<secreta>.bin com a POSIÇÃO de cada palavra do
// vocabulário (Uint16, mesma ordem do vocabulário; 0 = a própria secreta). As posições foram
// calculadas fora daqui com vetores de palavras (fastText) — a lista feita à mão continua
// vindo primeiro (#2, #3...), e o resto segue pela proximidade de sentido.
//
// Usado pelos dois servidores: o do PartyKit busca os arquivos pelos "assets" e o local lê do
// disco. `load(path)` devolve uma Promise de ArrayBuffer.

export function createContextoRanker(load) {
  let vocabPromise = null;
  let vocab = null; // Map palavra -> índice
  const ranks = new Map(); // secreta -> Uint16Array (só as últimas usadas)
  const pending = new Map();

  function loadVocab() {
    if (!vocabPromise) {
      vocabPromise = load('/contexto/vocabulario.txt').then((buf) => {
        const m = new Map();
        new TextDecoder().decode(buf).split('\n').forEach((w, i) => { if (w && !m.has(w)) m.set(w, i); });
        vocab = m;
        return m;
      }).catch((e) => { vocabPromise = null; throw e; });
    }
    return vocabPromise;
  }

  function loadRanks(secretNorm) {
    if (ranks.has(secretNorm)) return Promise.resolve(ranks.get(secretNorm));
    if (!pending.has(secretNorm)) {
      pending.set(secretNorm, load(`/contexto/${encodeURIComponent(secretNorm)}.bin`).then((buf) => {
        const arr = new Uint16Array(buf.slice(0));
        ranks.set(secretNorm, arr);
        while (ranks.size > 6) ranks.delete(ranks.keys().next().value);
        pending.delete(secretNorm);
        return arr;
      }).catch((e) => { pending.delete(secretNorm); throw e; }));
    }
    return pending.get(secretNorm);
  }

  return {
    /** começa a baixar o que a rodada vai precisar (chamado quando sorteia a palavra) */
    preload(secretNorm) {
      loadVocab().catch(() => {});
      loadRanks(secretNorm).catch(() => {});
    },
    /** posição do palpite, ou null se a palavra não está no vocabulário (ou não deu pra carregar) */
    async rankOf(secretNorm, guessNorm) {
      try {
        const [v, r] = await Promise.all([loadVocab(), loadRanks(secretNorm)]);
        const i = v.get(guessNorm);
        return i === undefined ? null : r[i];
      } catch (e) {
        return null;
      }
    },
    /** mesma coisa, sem esperar: só responde se já estiver carregado (servidor local) */
    rankOfSync(secretNorm, guessNorm) {
      const r = ranks.get(secretNorm);
      if (!vocab || !r) return null;
      const i = vocab.get(guessNorm);
      return i === undefined ? null : r[i];
    },
  };
}
