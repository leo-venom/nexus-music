package com.leo.nexusmusicapp;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * NEXUS MUSIC — app autônomo (100% no celular).
 *
 * Não precisa de computador nem de servidor externo:
 *   1. o app lê a biblioteca de músicas DO CELULAR (MediaStore);
 *   2. baixa do YouTube direto pela internet (NewPipe Extractor) — funciona
 *      em qualquer rede, inclusive dados móveis;
 *   3. serve a interface e os arquivos por um servidor HTTP local em
 *      127.0.0.1, que é o que o WebView carrega.
 */
public class MainActivity extends Activity {

    private static final String TAG = "NexusMusic";
    private static final int PEDIDO_PERMISSAO = 42;

    private WebView webView;
    private NexusServer servidor;
    private Library biblioteca;
    private YtDownload baixador;

    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private long ultimoToqueVoltar = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* é um player: mantém a tela acesa */
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        biblioteca = new Library(this);
        baixador = new YtDownload(this, biblioteca);

        /* sobe o servidor local e só então carrega a interface */
        try {
            servidor = new NexusServer(this, biblioteca, baixador);
            int porta = servidor.iniciar();
            Log.i(TAG, "porta local: " + porta);
            montarWebView("http://127.0.0.1:" + porta + "/");
        } catch (Exception e) {
            Log.e(TAG, "falha subindo o servidor: " + e);
            Toast.makeText(this, "Falha ao iniciar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pedirPermissoes();
    }

    // ------------------------------------------------------------------ //
    //  WebView
    // ------------------------------------------------------------------ //

    private void montarWebView(String endereco) {
        webView = new WebView(this);

        WebSettings cfg = webView.getSettings();
        cfg.setJavaScriptEnabled(true);
        cfg.setDomStorageEnabled(true);
        cfg.setDatabaseEnabled(true);
        cfg.setMediaPlaybackRequiresUserGesture(false);   /* toca sozinho */
        cfg.setBuiltInZoomControls(false);
        cfg.setDisplayZoomControls(false);
        cfg.setLoadWithOverviewMode(true);
        cfg.setUseWideViewPort(true);
        cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        cfg.setAllowFileAccess(false);                    /* tudo vem do servidor local */
        cfg.setAllowContentAccess(true);

        webView.setBackgroundColor(0xFF000000);
        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                webView.setVisibility(View.GONE);
                ((FrameLayout) findViewById(android.R.id.content)).addView(
                        fullscreenView, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }

            @Override
            public void onHideCustomView() {
                sairDaTelaCheia();
            }
        });

        /* O botão "⬇ DOWNLOAD" dos cards entrega o MP3 aqui: usamos o
           DownloadManager, então o arquivo vai para Downloads/NexusMusic/. */
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                try {
                    String nome = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request pedido = new DownloadManager.Request(Uri.parse(url));
                    pedido.setMimeType(mimeType);
                    pedido.addRequestHeader("User-Agent", userAgent);

                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        pedido.addRequestHeader("Cookie", cookies);
                    }

                    pedido.setTitle(nome);
                    pedido.setDescription("NEXUS MUSIC");
                    pedido.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    pedido.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, "NexusMusic/" + nome);

                    DownloadManager gerenciador =
                            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (gerenciador != null) {
                        gerenciador.enqueue(pedido);
                        Toast.makeText(MainActivity.this, "Baixando: " + nome,
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Falha ao baixar",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        setContentView(webView);
        webView.loadUrl(endereco);
    }

    private void sairDaTelaCheia() {
        if (fullscreenView == null) return;
        ((FrameLayout) fullscreenView.getParent()).removeView(fullscreenView);
        fullscreenView = null;
        webView.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Permissões + biblioteca
    // ------------------------------------------------------------------ //

    private void pedirPermissoes() {
        List<String> faltando = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                faltando.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                faltando.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            faltando.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (faltando.isEmpty()) {
            varrerBiblioteca();
            return;
        }
        requestPermissions(faltando.toArray(new String[0]), PEDIDO_PERMISSAO);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permissoes, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados);
        if (codigo != PEDIDO_PERMISSAO) return;

        boolean algumOk = false;
        for (int r : resultados) {
            if (r == PackageManager.PERMISSION_GRANTED) algumOk = true;
        }

        if (algumOk) {
            varrerBiblioteca();
        } else {
            Toast.makeText(this,
                    "Sem permissão para ler as músicas do celular.\n"
                            + "O app ainda pode baixar do YouTube.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Varre o MediaStore num thread (pode demorar com biblioteca grande). */
    private void varrerBiblioteca() {
        new Thread(() -> {
            biblioteca.recarregar();
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.reload();   /* a interface busca /api/music de novo */
                }
            });
        }, "nexus-scan").start();
    }

    // ------------------------------------------------------------------ //
    //  Ciclo de vida
    // ------------------------------------------------------------------ //

    @Override
    protected void onResume() {
        super.onResume();
        /* voltou ao app: procura música nova (downloads, arquivos copiados) */
        if (biblioteca != null) {
            new Thread(biblioteca::recarregar, "nexus-scan").start();
        }
    }

    @Override
    protected void onDestroy() {
        if (servidor != null) servidor.parar();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            sairDaTelaCheia();
            return;
        }

        /* A interface registra cada nível (pastas e a página inicial) no
           histórico do navegador, então `goBack()` volta uma pasta e, na
           raiz, chega à PÁGINA INICIAL. */
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        /* Já na página inicial: pede confirmação em vez de fechar de uma vez. */
        long agora = SystemClock.elapsedRealtime();
        if (agora - ultimoToqueVoltar < 2000) {
            super.onBackPressed();
        } else {
            ultimoToqueVoltar = agora;
            Toast.makeText(this, "Toque em voltar de novo para sair",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
