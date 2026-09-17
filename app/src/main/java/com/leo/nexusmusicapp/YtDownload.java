package com.leo.nexusmusicapp;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;

/**
 * Download do YouTube DENTRO do app — funciona em qualquer rede (dados móveis,
 * Wi-Fi de terceiros), sem precisar do computador.
 *
 * Usa o **NewPipe Extractor** (Java puro, mantido pela equipe do NewPipe) para
 * descobrir o stream de áudio, e baixa o arquivo direto.
 *
 * Salva em `Music/NexusMusic/YOUTUBE/` via MediaStore, então a música aparece
 * para os outros players do celular também. A capa vai para a pasta privada do
 * app (servida em `/cover/<id>`).
 *
 * ⚠️ O YouTube muda com frequência: se parar de baixar, é sinal de que o
 * NewPipe Extractor precisa de uma versão mais nova — basta atualizar a
 * dependência no `app/build.gradle` e recompilar.
 */
public class YtDownload {

    private static final String TAG = "NexusYt";

    /** Pasta, dentro de Music/, onde os downloads são gravados. */
    private static final String SUBPASTA = "Music/NexusMusic/YOUTUBE";

    private final Context ctx;
    private final Library library;

    // -------- estado do job (lido pelo /api/youtube/status) -------- //
    private volatile boolean ativo = false;
    private volatile double percentual = 0;
    private volatile String fase = "parado";
    private volatile String etapa = "";
    private volatile String titulo = "";
    private volatile String erro = "";
    private volatile String mensagem = "";

    private static boolean newPipePronto = false;

    public YtDownload(Context ctx, Library library) {
        this.ctx = ctx;
        this.library = library;
    }

    // ------------------------------------------------------------------ //
    //  Inicialização do NewPipe
    // ------------------------------------------------------------------ //

    public static synchronized void preparar() {
        if (newPipePronto) return;
        NewPipe.init(new NexusDownloader());
        newPipePronto = true;
        Log.i(TAG, "NewPipe Extractor pronto");
    }

    /**
     * Implementação mínima do Downloader do NewPipe usando HttpURLConnection —
     * evita trazer OkHttp só para isso.
     */
    static class NexusDownloader extends Downloader {
        @Override
        public Response execute(@NonNull Request request) throws IOException, ReCaptchaException {
            HttpURLConnection conexao = (HttpURLConnection) new URL(request.url()).openConnection();
            conexao.setRequestMethod(request.httpMethod());
            conexao.setConnectTimeout(20000);
            conexao.setReadTimeout(20000);
            conexao.setInstanceFollowRedirects(true);

            for (Map.Entry<String, List<String>> cabecalho : request.headers().entrySet()) {
                for (String valor : cabecalho.getValue()) {
                    conexao.addRequestProperty(cabecalho.getKey(), valor);
                }
            }

            byte[] dados = request.dataToSend();
            if (dados != null && dados.length > 0) {
                conexao.setDoOutput(true);
                try (OutputStream os = conexao.getOutputStream()) {
                    os.write(dados);
                }
            }

            int codigo = conexao.getResponseCode();
            String mensagemHttp = conexao.getResponseMessage();

            InputStream fluxo = codigo >= 400 ? conexao.getErrorStream() : conexao.getInputStream();
            String corpo = fluxo == null ? "" : lerTexto(fluxo);

            return new Response(codigo, mensagemHttp, conexao.getHeaderFields(), corpo,
                    conexao.getURL().toString());
        }

        private static String lerTexto(InputStream is) throws IOException {
            try (InputStream entrada = is) {
                java.io.ByteArrayOutputStream saida = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = entrada.read(buffer)) > 0) {
                    saida.write(buffer, 0, n);
                }
                return saida.toString("UTF-8");
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  API do job
    // ------------------------------------------------------------------ //

    public boolean ativo() {
        return ativo;
    }

    public synchronized String iniciar(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "{\"ok\":false,\"erro\":\"Informe o link do YouTube\"}";
        }
        String limpa = url.trim();
        if (!limpa.contains("youtube.com") && !limpa.contains("youtu.be")) {
            return "{\"ok\":false,\"erro\":\"Só links do YouTube\"}";
        }
        if (ativo) {
            return "{\"ok\":false,\"erro\":\"Já existe um download em andamento\"}";
        }

        ativo = true;
        percentual = 0;
        fase = "baixando";
        etapa = "preparando";
        titulo = "";
        erro = "";
        mensagem = "";

        Thread t = new Thread(() -> baixar(limpa), "nexus-yt");
        t.setDaemon(true);
        t.start();

        return "{\"ok\":true}";
    }

    public String statusJson() {
        StringBuilder json = new StringBuilder(200);
        json.append("{\"ativo\":").append(ativo)
                .append(",\"percentual\":").append(String.format(Locale.US, "%.1f", percentual))
                .append(",\"fase\":\"").append(NexusServer.escapar(fase)).append("\"")
                .append(",\"etapa\":\"").append(NexusServer.escapar(etapa)).append("\"")
                .append(",\"titulo\":\"").append(NexusServer.escapar(titulo)).append("\"")
                .append(",\"erro\":\"").append(NexusServer.escapar(erro)).append("\"")
                .append(",\"mensagem\":\"").append(NexusServer.escapar(mensagem)).append("\"}");
        return json.toString();
    }

    // ------------------------------------------------------------------ //
    //  O trabalho
    // ------------------------------------------------------------------ //

    private void baixar(String url) {
        try {
            preparar();

            fase = "baixando";
            etapa = "lendo o vídeo";
            percentual = 0;

            StreamExtractor extrator = ServiceList.YouTube.getStreamExtractor(url);
            extrator.fetchPage();

            titulo = extrator.getName();
            String canal = extrator.getUploaderName();

            /* na v0.26.5 as thumbs vêm numa lista de Image (não existe mais
               getThumbnailUrl()); a primeira é a de maior resolução. */
            String thumb = null;
            List<Image> thumbs = extrator.getThumbnails();
            if (thumbs != null && !thumbs.isEmpty()) {
                thumb = thumbs.get(0).getUrl();
            }
            if (canal == null || canal.isEmpty()) canal = "YouTube";

            /* --- escolhe o melhor áudio: prefere M4A (toca em tudo) --- */
            List<AudioStream> audios = extrator.getAudioStreams();
            if (audios == null || audios.isEmpty()) {
                throw new IOException("Não encontrei faixa de áudio nesse vídeo");
            }

            AudioStream escolhido = null;
            for (AudioStream a : audios) {
                MediaFormat formato = a.getFormat();
                boolean m4a = formato == MediaFormat.M4A;
                if (escolhido == null) {
                    escolhido = a;
                    continue;
                }
                boolean escolhidoM4a = escolhido.getFormat() == MediaFormat.M4A;
                if (m4a && !escolhidoM4a) {
                    escolhido = a;
                } else if (m4a == escolhidoM4a
                        && a.getAverageBitrate() > escolhido.getAverageBitrate()) {
                    escolhido = a;
                }
            }

            etapa = "baixando o áudio";
            String ext = escolhido.getFormat() == MediaFormat.M4A ? "m4a" : "webm";
            String tipo = escolhido.getFormat() == MediaFormat.M4A ? "audio/mp4" : "audio/webm";

            String nomeBase = nomeSeguro(titulo);
            String nomeArquivo = nomeBase + "." + ext;

            Uri destino = criarDestino(nomeArquivo, tipo);
            baixarRapido(escolhido.getUrl(), destino);

            percentual = 100;
            etapa = "finalizando";

            /* no Android 10+ o arquivo nasce "pendente" para não aparecer pela
               metade na galeria/players; agora que terminou, libera. */
            liberarPendente(destino);

            /* --- grava metadados que o MediaStore aceita --- */
            ContentValues tags = new ContentValues();
            tags.put(MediaStore.Audio.Media.TITLE, titulo);
            tags.put(MediaStore.Audio.Media.ARTIST, canal);
            tags.put(MediaStore.Audio.Media.ALBUM, "NEXUS MUSIC · YouTube");
            tags.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            try {
                ctx.getContentResolver().update(destino, tags, null, null);
            } catch (Exception ignored) {
            }

            /* --- capa: baixa a thumb para a pasta privada do app --- */
            String id = destino.getLastPathSegment();
            if (thumb != null && !thumb.isEmpty() && id != null && id.matches("\\d+")) {
                try {
                    baixarCapa(thumb, id);
                } catch (Exception e) {
                    Log.w(TAG, "capa: " + e.getMessage());
                }
            }

            /* --- a biblioteca recarrega para a música aparecer na hora --- */
            library.recarregar();

            fase = "concluido";
            etapa = "";
            mensagem = "✔ " + titulo;

        } catch (Exception e) {
            Log.w(TAG, "falha no download: " + e);
            fase = "erro";
            erro = mensagemDeErro(e);
        } finally {
            ativo = false;
        }
    }

    private String mensagemDeErro(Exception e) {
        String texto = String.valueOf(e.getMessage());
        if (texto.contains("reCaptcha") || texto.contains("Recaptcha")) {
            return "O YouTube pediu verificação — tente outro link";
        }
        if (texto.contains("Unable to resolve host") || texto.contains("UnknownHost")) {
            return "Sem conexão com a internet";
        }
        if (texto.length() > 120) {
            return texto.substring(0, 120);
        }
        return texto;
    }

    // ------------------------------------------------------------------ //
    //  Gravação
    // ------------------------------------------------------------------ //

    private Uri criarDestino(String nomeArquivo, String tipoAudio) {
        ContentResolver resolver = ctx.getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues valores = new ContentValues();
            valores.put(MediaStore.Audio.Media.DISPLAY_NAME, nomeArquivo);
            valores.put(MediaStore.Audio.Media.MIME_TYPE, tipoAudio);
            valores.put(MediaStore.Audio.Media.RELATIVE_PATH, SUBPASTA);
            valores.put(MediaStore.Audio.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, valores);
            if (uri == null) {
                throw new IllegalStateException("MediaStore recusou o arquivo");
            }
            /* libera o arquivo quando o download terminar */
            ContentValues pronto = new ContentValues();
            pronto.put(MediaStore.Audio.Media.IS_PENDING, 0);
            return uri;
        }

        /* Android 9 ou anterior: grava direto na pasta pública */
        File pasta = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "NexusMusic/YOUTUBE");
        if (!pasta.exists() && !pasta.mkdirs()) {
            throw new IllegalStateException("Não consegui criar a pasta " + pasta);
        }
        File arquivo = new File(pasta, nomeArquivo);
        ContentValues valores = new ContentValues();
        valores.put(MediaStore.Audio.Media.DATA, arquivo.getAbsolutePath());
        return Uri.fromFile(arquivo);
    }

    /** Solta o arquivo no MediaStore depois de escrever. */
    public void liberarPendente(Uri destino) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues pronto = new ContentValues();
            pronto.put(MediaStore.Audio.Media.IS_PENDING, 0);
            try {
                ctx.getContentResolver().update(destino, pronto, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Download em PARALELO (o grande ganho de velocidade)
    // ------------------------------------------------------------------ //

    /**
     * Baixa o áudio com **várias conexões simultâneas** (Range requests).
     *
     * Por que isso importa: o YouTube limita a taxa **por conexão**, então um
     * download sequencial fica lento mesmo com internet boa. Dividindo o arquivo
     * em partes e baixando em paralelo, a velocidade final é a soma das conexões.
     * É exatamente o que o yt-dlp faz com `--concurrent-fragments`, e também como
     * qualquer gerenciador de downloads (IDM, aria2) acelera as coisas.
     */
    private void baixarRapido(String url, Uri destino) throws Exception {
        long total = descobrirTamanho(url);

        /* arquivo pequeno ou tamanho desconhecido: uma conexão já resolve */
        if (total <= 0 || total < 1024 * 1024) {
            try (InputStream entrada = abrirStream(url);
                 OutputStream saida = ctx.getContentResolver().openOutputStream(destino)) {
                if (saida == null) throw new IOException("Não consegui gravar o arquivo");
                byte[] buffer = new byte[128 * 1024];
                long baixado = 0;
                int n;
                while ((n = entrada.read(buffer)) > 0) {
                    saida.write(buffer, 0, n);
                    baixado += n;
                    if (total > 0) percentual = Math.min(99.0, baixado * 100.0 / total);
                }
            }
            return;
        }

        final int conexoes = 5;
        final long tamanhoParte = total / conexoes + 1;

        final java.util.concurrent.atomic.AtomicLong baixado =
                new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicReference<String> falha =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        final List<File> partes = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < conexoes; i++) {
            final long inicio = i * tamanhoParte;
            final long fim = Math.min(inicio + tamanhoParte - 1, total - 1);
            if (inicio >= total) break;

            final File temporario = new File(ctx.getCacheDir(), "nexus_part_" + i + "_"
                    + System.nanoTime());
            partes.add(temporario);

            Thread t = new Thread(() -> {
                try {
                    baixarParte(url, inicio, fim, temporario, baixado);
                } catch (Exception e) {
                    falha.compareAndSet(null, String.valueOf(e.getMessage()));
                }
            }, "nexus-parte-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        /* acompanha o progresso e mostra a VELOCIDADE na tela */
        long marco = System.currentTimeMillis();
        long ultimoTotal = 0;
        while (true) {
            boolean algumaViva = false;
            for (Thread t : threads) {
                if (t.isAlive()) {
                    algumaViva = true;
                    break;
                }
            }
            if (!algumaViva) break;

            Thread.sleep(600);
            long agora = System.currentTimeMillis();
            long atual = baixado.get();
            double segundos = (agora - marco) / 1000.0;
            if (segundos > 0) {
                double mbPorSegundo = (atual - ultimoTotal) / segundos / (1024.0 * 1024.0);
                percentual = Math.min(99.0, atual * 100.0 / total);
                etapa = String.format(Locale.US, "baixando · %.1f MB/s", mbPorSegundo);
            }
            marco = agora;
            ultimoTotal = atual;

            if (falha.get() != null) break;
        }

        for (Thread t : threads) {
            t.join(3000);
        }

        if (falha.get() != null) {
            for (File f : partes) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            throw new IOException(falha.get());
        }

        /* junta as partes, na ordem, dentro do arquivo final */
        try (OutputStream saida = ctx.getContentResolver().openOutputStream(destino)) {
            if (saida == null) throw new IOException("Não consegui gravar o arquivo");
            byte[] buffer = new byte[128 * 1024];
            for (File parte : partes) {
                try (InputStream is = new BufferedInputStream(
                        new java.io.FileInputStream(parte), 128 * 1024)) {
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        saida.write(buffer, 0, n);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                parte.delete();
            }
        }
    }

    /** Baixa um pedaço (Range) para um arquivo temporário. */
    private void baixarParte(String url, long inicio, long fim, File destino,
                             java.util.concurrent.atomic.AtomicLong contador) throws IOException {
        HttpURLConnection conexao = abrirConexao(url);
        conexao.setRequestProperty("Range", "bytes=" + inicio + "-" + fim);

        try (InputStream entrada = new BufferedInputStream(conexao.getInputStream(), 128 * 1024);
             OutputStream saida = new java.io.BufferedOutputStream(
                     new FileOutputStream(destino), 128 * 1024)) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = entrada.read(buffer)) > 0) {
                saida.write(buffer, 0, n);
                contador.addAndGet(n);
            }
        } finally {
            conexao.disconnect();
        }
    }

    /**
     * Descobre o tamanho do áudio sem baixar tudo: pede 1 byte e lê o
     * `Content-Range` ("bytes 0-0/12345678").
     */
    private long descobrirTamanho(String url) {
        try {
            HttpURLConnection conexao = abrirConexao(url);
            conexao.setRequestProperty("Range", "bytes=0-0");
            int codigo = conexao.getResponseCode();

            if (codigo == 206) {
                String faixa = conexao.getHeaderField("Content-Range");
                if (faixa != null && faixa.contains("/")) {
                    long tamanho = Long.parseLong(faixa.substring(faixa.indexOf('/') + 1).trim());
                    conexao.disconnect();
                    return tamanho;
                }
            }
            long tamanho = conexao.getContentLengthLong();
            conexao.disconnect();
            return tamanho;
        } catch (Exception e) {
            Log.w(TAG, "tamanho: " + e.getMessage());
            return -1;
        }
    }

    private InputStream abrirStream(String url) throws IOException {
        HttpURLConnection conexao = abrirConexao(url);
        return new BufferedInputStream(conexao.getInputStream(), 128 * 1024);
    }

    /** Conexão padronizada (UA de navegador + sem compressão, senão o Range quebra). */
    private HttpURLConnection abrirConexao(String url) throws IOException {
        HttpURLConnection conexao = (HttpURLConnection) new URL(url).openConnection();
        conexao.setConnectTimeout(20000);
        conexao.setReadTimeout(30000);
        conexao.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/110.0 Mobile Safari/537.36");
        conexao.setRequestProperty("Accept", "*/*");
        /* sem compressão: precisamos dos bytes exatos para o Range funcionar */
        conexao.setRequestProperty("Accept-Encoding", "identity");
        return conexao;
    }

    private void baixarCapa(String url, String id) throws IOException {
        HttpURLConnection conexao = (HttpURLConnection) new URL(url).openConnection();
        conexao.setConnectTimeout(15000);
        conexao.setReadTimeout(15000);
        try (InputStream entrada = conexao.getInputStream();
             FileOutputStream saida = new FileOutputStream(
                     new File(library.pastaCapas(), id + ".jpg"))) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = entrada.read(buffer)) > 0) {
                saida.write(buffer, 0, n);
            }
        }
    }

    /** Nome de arquivo seguro (sem barras, dois-pontos nem caracteres de controle). */
    static String nomeSeguro(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "audio";
        }
        String limpo = texto.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (limpo.length() > 90) {
            limpo = limpo.substring(0, 90).trim();
        }
        return limpo.isEmpty() ? "audio" : limpo;
    }
}
