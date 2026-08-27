# Festa Sync — versão hospedada no seu PC

Mesmo app de antes (fila do YouTube sincronizada), mas agora a sincronia é feita
por um servidorzinho Node.js seu (Express + WebSocket) em vez do storage do Claude.
Isso significa que você pode rodar isso onde quiser — inclusive sem internet nenhuma,
se a galera estiver na mesma rede.

## 1. Pré-requisito

Precisa ter Node.js 18+ instalado. Confirma com:

```bash
node -v
```

Se não tiver, baixa a versão LTS em https://nodejs.org.

## 2. Instalar e rodar

Dentro desta pasta:

```bash
npm install
npm start
```

Isso sobe o servidor em `http://localhost:3000`. Abre esse link no seu navegador
pra testar sozinha primeiro.

## 3. Deixar acessível pra galera

### Opção 0 — mesma rede Wi-Fi (mais simples, zero ferramentas extra)

Se a galera vai estar na sua casa/mesma rede, não precisa de túnel nenhum.

1. Descubra seu IP local:
   - Linux/Mac: `hostname -I` (ou `ifconfig`)
   - Windows: `ipconfig` (procure "Endereço IPv4")
2. Manda pra galera: `http://SEU_IP_LOCAL:3000` (ex: `http://192.168.0.15:3000`)
3. Se ninguém conseguir abrir, o firewall do seu PC pode estar bloqueando a porta
   3000 pra conexões de entrada — libera essa porta pra rede local.

### Opção A — ngrok (acesso pela internet, redes diferentes)

1. Cria conta grátis em https://ngrok.com e pega seu authtoken.
2. Configura uma vez: `npx ngrok config add-authtoken SEU_TOKEN`
3. Com o servidor rodando, em outro terminal: `npx ngrok http 3000`
4. Ele te dá um link tipo `https://alguma-coisa.ngrok-free.app` — manda esse pra galera.

⚠️ No plano grátis o link muda toda vez que você reinicia o ngrok.

### Opção B — Cloudflare Tunnel (sem precisar de conta)

1. Instala o `cloudflared`:
   https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
2. Com o servidor rodando: `cloudflared tunnel --url http://localhost:3000`
3. Ele te dá um link `https://alguma-coisa.trycloudflare.com` — manda pra galera.

Tanto ngrok quanto Cloudflare Tunnel funcionam fazendo uma conexão de *saída* do
seu PC — não precisa mexer em configuração de roteador nem abrir porta pra fora.

## 4. Compartilhar com a galera

Manda o link (local, ngrok ou cloudflare) pro grupo. O botão de copiar (⧉) dentro
do app já gera o link com `?room=CODIGO` preenchido, então quem clicar já cai
direto na sala certa — só precisa escrever o nome e confirmar.

## 5. Encerrar a festa

`Ctrl+C` no terminal do `node server.js`, e no terminal do túnel (ngrok/cloudflared),
se estiver usando.

## Observações importantes

- **Sem persistência**: o estado (fila, música tocando) fica só na memória do
  processo. Se derrubar o servidor, zera — de propósito, pra manter simples.
- **Sem login/senha**: quem tiver o link entra e pode tocar/pausar/adicionar
  músicas. Ótimo pra galera de confiança; não é pra postar publicamente.
- Vídeos com **incorporação desativada** pelo dono continuam não tocando —
  isso é restrição do YouTube, sem contorno possível.
- **Avanço automático**: quando a música termina, a próxima da fila toca sozinha
  (detectado via evento `ENDED` da API do YouTube). No fim da fila, o player só para.
- Peça pra galera usar os botões do app (não clicar direto no vídeo) — clique
  direto no player não é detectado pelo servidor e desincroniza a pessoa.
