package com.leo.nexusmusicapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Serviço do player: mantém uma MediaSession viva para que a **tela de bloqueio**,
 * a **notificação** e os **botões do fone de ouvido** controlem a música.
 *
 * ── Por que isto é necessário ─────────────────────────────────────────────
 * O áudio toca dentro do `WebView`, e o WebView **não publica** a MediaSession
 * dele para o sistema (o `navigator.mediaSession` do JavaScript não sai do
 * WebView). Sem uma sessão nativa, o Android não sabe que há música tocando:
 * nada aparece na tela bloqueada e os botões do fone não fazem nada.
 *
 * ── Como conversa com a interface ─────────────────────────────────────────
 * O fluxo é de mão dupla:
 *   • interface → nativo:  AndroidMedia.atualizar(titulo, artista, tocando)
 *   • nativo → interface:  webView.evaluateJavascript("nexusControle('next')")
 *
 * O serviço roda em **primeiro plano** enquanto há música: é o que mantém o
 * app vivo em segundo plano e a notificação na tela bloqueada.
 */
public class PlayerService extends Service {

    private static final String TAG = "NexusPlayer";
    private static final String CANAL_ID = "nexus_player";
    private static final int ID_NOTIFICACAO = 8477;

    /** Ações da notificação (também usadas como ações de Intent). */
    public static final String ACAO_TOGGLE = "com.leo.nexusmusicapp.TOGGLE";
    public static final String ACAO_PROXIMA = "com.leo.nexusmusicapp.PROXIMA";
    public static final String ACAO_ANTERIOR = "com.leo.nexusmusicapp.ANTERIOR";

    /** Metadados da faixa, entregues por Intent (funciona desde a 1ª chamada). */
    private static final String EXTRA_TITULO = "nexus.titulo";
    private static final String EXTRA_ARTISTA = "nexus.artista";
    private static final String EXTRA_TOCANDO = "nexus.tocando";

    private MediaSession sessao;
    private String titulo = "";
    private String artista = "";
    private boolean tocando;
    private boolean emPrimeiroPlano;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void onCreate() {
        super.onCreate();
        criarCanal();

        sessao = new MediaSession(this, "NEXUS MUSIC");

        /* É este callback que recebe os botões do fone e os controles
           da tela de bloqueio. Cada ação vira uma chamada ao JavaScript. */
        sessao.setCallback(new MediaSession.Callback() {

            @Override
            public void onPlay() {
                avisarInterface("play");
            }

            @Override
            public void onPause() {
                avisarInterface("pause");
            }

            @Override
            public void onSkipToNext() {
                avisarInterface("next");
            }

            @Override
            public void onSkipToPrevious() {
                avisarInterface("prev");
            }

            @Override
            public void onStop() {
                avisarInterface("pause");
            }
        });

        sessao.setActive(true);
        Log.i(TAG, "MediaSession ativa");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        String acao = intent == null ? null : intent.getAction();

        if (ACAO_TOGGLE.equals(acao)) {
            avisarInterface("toggle");
        } else if (ACAO_PROXIMA.equals(acao)) {
            avisarInterface("next");
        } else if (ACAO_ANTERIOR.equals(acao)) {
            avisarInterface("prev");
        }

        /* Os dados da faixa viajam na própria Intent: assim funciona já na
           PRIMEIRA chamada, quando o serviço ainda está sendo criado e uma
           instância estática ainda não existiria. */
        if (intent != null && intent.hasExtra(EXTRA_TITULO)) {
            atualizar(
                    intent.getStringExtra(EXTRA_TITULO),
                    intent.getStringExtra(EXTRA_ARTISTA),
                    intent.getBooleanExtra(EXTRA_TOCANDO, false));
        }

        /* START_STICKY não: se o sistema matar, a música também morreu. */
        return START_NOT_STICKY;
    }

    /** Garante o serviço rodando e manda os metadados por Intent. */
    public static void atualizar(Context ctx, String titulo, String artista, boolean tocando) {
        Intent intent = new Intent(ctx, PlayerService.class);
        intent.putExtra(EXTRA_TITULO, titulo == null ? "" : titulo);
        intent.putExtra(EXTRA_ARTISTA, artista == null ? "" : artista);
        intent.putExtra(EXTRA_TOCANDO, tocando);
        ctx.startService(intent);
    }

    /** Pede para encerrar o serviço (o app inteiro foi fechado). */
    public static void encerrar(Context ctx) {
        ctx.stopService(new Intent(ctx, PlayerService.class));
    }

    @Override
    public void onDestroy() {
        if (sessao != null) {
            sessao.setActive(false);
            sessao.release();
            sessao = null;
        }
        emPrimeiroPlano = false;
        super.onDestroy();
        Log.i(TAG, "servico encerrado");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ interface ↔ nativo

    /** Manda a ação para a interface (que é quem realmente toca o áudio). */
    private void avisarInterface(String acao) {
        Log.i(TAG, "controle recebido do sistema: " + acao);
        MainActivity.chamarJs("nexusControle('" + acao + "')");
    }

    /**
     * Chamado pelo JavaScript sempre que a faixa ou o estado muda.
     * Atualiza a MediaSession (metadados + estado) e a notificação.
     */
    public void atualizar(String novoTitulo, String novoArtista, boolean estaTocando) {
        this.titulo = novoTitulo == null ? "" : novoTitulo;
        this.artista = novoArtista == null ? "" : novoArtista;
        this.tocando = estaTocando;

        if (sessao == null) {
            return;
        }

        // ---- metadados (o que a tela bloqueada mostra)
        MediaMetadata metadados = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artista)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "NEXUS MUSIC")
                .build();
        sessao.setMetadata(metadados);

        // ---- estado (faz o Android desenhar play ou pause)
        PlaybackState estado = new PlaybackState.Builder()
                .setActions(
                        PlaybackState.ACTION_PLAY
                                | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SKIP_TO_NEXT
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                | PlaybackState.ACTION_STOP)
                .setState(
                        tocando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1.0f)
                .build();
        sessao.setPlaybackState(estado);

        // ---- notificação (e o serviço em primeiro plano)
        Notification notificacao = montarNotificacao();

        if (tocando || emPrimeiroPlano) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_NOTIFICACAO, notificacao,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(ID_NOTIFICACAO, notificacao);
            }
            emPrimeiroPlano = true;
        } else {
            // pausado: tira do primeiro plano, mas mantém a notificação
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            emPrimeiroPlano = false;
            gerenciador().notify(ID_NOTIFICACAO, notificacao);
        }
    }

    // ------------------------------------------------------------------ notificação

    private void criarCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel canal = new NotificationChannel(
                CANAL_ID,
                "Reprodutor",
                NotificationManager.IMPORTANCE_LOW);
        canal.setDescription("Controles da música que está tocando");
        canal.setShowBadge(false);
        gerenciador().createNotificationChannel(canal);
    }

    private NotificationManager gerenciador() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private PendingIntent pendente(String acao) {
        Intent intent = new Intent(this, PlayerService.class).setAction(acao);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, acao.hashCode(), intent, flags);
    }

    private Notification montarNotificacao() {

        Notification.Action anterior = new Notification.Action.Builder(
                android.R.drawable.ic_media_previous,
                "Anterior",
                pendente(ACAO_ANTERIOR)).build();

        Notification.Action alternar = new Notification.Action.Builder(
                tocando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                tocando ? "Pausar" : "Tocar",
                pendente(ACAO_TOGGLE)).build();

        Notification.Action proxima = new Notification.Action.Builder(
                android.R.drawable.ic_media_next,
                "Próxima",
                pendente(ACAO_PROXIMA)).build();

        Notification.MediaStyle estilo = new Notification.MediaStyle()
                .setMediaSession(sessao.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);

        // tocar na notificação abre o app
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent conteudo = PendingIntent.getActivity(this, 0, abrir, flags);

        Notification.Builder construtor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CANAL_ID)
                : new Notification.Builder(this);

        return construtor
                .setContentTitle(titulo.isEmpty() ? "NEXUS MUSIC" : titulo)
                .setContentText(artista.isEmpty() ? "NEXUS MUSIC" : artista)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(conteudo)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(tocando)
                .setOnlyAlertOnce(true)
                .addAction(anterior)
                .addAction(alternar)
                .addAction(proxima)
                .setStyle(estilo)
                .build();
    }
}
