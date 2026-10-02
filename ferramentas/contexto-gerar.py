"""Gera public/contexto/ (vocabulário + posição de cada palavra pra cada palavra secreta).

Como rodar (precisa de numpy e ~350 MB de disco temporário):
  curl -sL https://dl.fbaipublicfiles.com/fasttext/vectors-crawl/cc.pt.300.vec.gz | gunzip | head -n 150001 > cc150k.vec
  node -e "const s=require('fs').readFileSync('party/server.js','utf8');const m=s.match(/const CONTEXTO_BANK = (\\[[\\s\\S]*?\\n\\]);/);require('fs').writeFileSync('bank.json',JSON.stringify(eval(m[1])))"
  python3 ferramentas/contexto-gerar.py cc150k.vec bank.json

Pra adicionar uma palavra secreta: põe { word: '...', related: [] } no CONTEXTO_BANK dos DOIS
servidores (party/server.js e server.js) e roda de novo. O arquivo do fastText vem ordenado
por frequência, então as primeiras linhas já são as palavras mais comuns.
"""
import json, re, sys, unicodedata
import numpy as np

VEC, BANK = sys.argv[1], sys.argv[2]
OUT = 'public/contexto/'
ok = re.compile(r'^[a-záàâãéêíóôõúüç]+(-[a-záàâãéêíóôõúüç]+)*$')

def norm(w):  # igual normalizeWord() dos servidores
    return re.sub(r'\s+', ' ', ''.join(c for c in unicodedata.normalize('NFD', w) if unicodedata.category(c) != 'Mn').lower().strip())

words, vecs, seen = [], [], set()
with open(VEC, encoding='utf-8', errors='ignore') as f:
    next(f)
    for line in f:
        sp = line.rstrip().split(' ')
        if len(sp) != 301 or not ok.match(sp[0]) or len(sp[0]) < 2: continue
        n = norm(sp[0])
        if n in seen: continue
        seen.add(n); words.append(n); vecs.append(np.array(sp[1:], dtype=np.float32))
        if len(words) >= 40000: break
M = np.vstack(vecs); M /= np.linalg.norm(M, axis=1, keepdims=True)
idx = {w: i for i, w in enumerate(words)}
open(OUT + 'vocabulario.txt', 'w').write('\n'.join(words) + '\n')

for b in json.load(open(BANK)):
    s = norm(b['word'])
    if s not in idx: print('fora do vocabulário, pulei:', b['word']); continue
    cur = []  # lista feita à mão vem primeiro (#2, #3...)
    for r in b['related']:
        n = norm(r)
        if n != s and n not in cur: cur.append(n)
    rank = np.zeros(len(words), dtype=np.uint16)
    for i, n in enumerate(cur):
        if n in idx: rank[idx[n]] = i + 1
    pos = len(cur)
    for i in np.argsort(-(M @ M[idx[s]])):
        if words[i] == s or words[i] in cur: continue
        pos += 1; rank[i] = min(pos, 65535)
    rank[idx[s]] = 0
    rank.astype('<u2').tofile(OUT + s + '.bin')
print('ok')
