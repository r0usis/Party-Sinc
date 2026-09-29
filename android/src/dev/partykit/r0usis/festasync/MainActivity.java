package dev.partykit.r0usis.festasync;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

// App Android do Festa Sync: só uma "casca" em tela cheia em volta do site. Tudo que o app
// faz (salas, música, chat, jogos) vem do site — atualizou o site, o app já está atualizado.
// O que fica aqui é só o que um navegador faz sozinho e o WebView não: pedir o microfone pro
// Android, abrir o seletor de arquivo, mandar links de fora pro navegador etc.
public class MainActivity extends Activity {
    static final String HOST = "festa-sync.r0usis.partykit.dev";
    static final String HOME = "https://" + HOST + "/";
    static final int REQ_MIC = 1;
    static final int REQ_FILE = 2;

    WebView web;
    PermissionRequest pendingMicRequest;
    ValueCallback<Uri[]> pendingFileCallback;

    // mostrada quando não dá pra abrir o site (sem internet, servidor fora do ar...)
    static final String OFFLINE_HTML =
        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
        + "<body style='margin:0;background:#0B0710;color:#F6EEFF;font-family:sans-serif;display:flex;"
        + "align-items:center;justify-content:center;height:100vh;text-align:center'><div style='padding:24px'>"
        + "<div style='font-size:44px'>📡</div><h2 style='margin:12px 0 6px'>Sem conexão</h2>"
        + "<p style='color:#B9A8D6;font-size:14px;line-height:1.5'>Não consegui abrir o Festa Sync.<br>Confere a internet e tenta de novo.</p>"
        + "<button onclick=\"location.href='" + HOME + "'\" style='margin-top:14px;padding:12px 22px;border:none;"
        + "border-radius:10px;background:#FF3D81;color:#1a0a12;font-weight:700;font-size:15px'>Tentar de novo</button>"
        + "</div></body></html>";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // festa com música tocando: a tela não apaga sozinha enquanto o app está aberto
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0710"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // localStorage: nome, id da pessoa, volume, preferências
        s.setDatabaseEnabled(true);
        // sem isso o vídeo/áudio só tocaria depois de um toque — a música que chega sincronizada
        // da sala e a voz do chat ficariam mudas
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true); // window.open / links "_blank" passam por onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " FestaSyncAndroid/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true); // player do YouTube

        web.setWebViewClient(new PageClient());
        web.setWebChromeClient(new ChromeClient());
        web.loadUrl(urlFromIntent(getIntent()));
    }

    // link de sala aberto de fora (WhatsApp etc.) vem direto pra sala; senão, página inicial
    String urlFromIntent(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && HOST.equals(data.getHost()) && "https".equals(data.getScheme())) return data.toString();
        return HOME;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) web.loadUrl(urlFromIntent(intent));
    }

    void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException e) { /* sem navegador instalado — não tem o que fazer */ }
    }

    class PageClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (HOST.equals(uri.getHost())) return false; // o próprio site continua no app
            openExternal(uri); // qualquer outro (YouTube, link do chat) abre no navegador
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null);
        }
    }

    class ChromeClient extends WebChromeClient {
        // O site pede o microfone (chat de voz, Mimic Party) -> pede pro Android, se ainda não
        // tiver, e só então libera pro site. Câmera/tela não são liberadas (o site não usa câmera,
        // e compartilhar tela não existe no Android — o site esconde esse botão sozinho).
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() {
                public void run() {
                    boolean wantsMic = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    }
                    Uri origin = request.getOrigin();
                    if (!wantsMic || origin == null || !HOST.equals(origin.getHost())) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                    } else {
                        if (pendingMicRequest != null) pendingMicRequest.deny();
                        pendingMicRequest = request;
                        requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_MIC);
                    }
                }
            });
        }

        // <input type="file"> — foto no chat, áudio pra biblioteca do Mimic Party
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                return false;
            }
            return true;
        }

        // window.open / link com target="_blank": o endereço vai pro navegador do celular
        // (senão o site trocaria de página DENTRO do app e a pessoa sairia da sala)
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView catcher = new WebView(MainActivity.this);
            catcher.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    openExternal(request.getUrl());
                    v.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(catcher);
            resultMsg.sendToTarget();
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC || pendingMicRequest == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingMicRequest.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
        } else {
            pendingMicRequest.deny();
            Toast.makeText(this, "Sem permissão de microfone — dá pra liberar nas configurações do app", Toast.LENGTH_LONG).show();
        }
        pendingMicRequest = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || pendingFileCallback == null) return;
        pendingFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        pendingFileCallback = null;
    }

    // "voltar" não fecha o app no meio da festa: só manda pra segundo plano (a música e a voz
    // continuam — de propósito o WebView não é pausado quando o app sai da tela)
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
