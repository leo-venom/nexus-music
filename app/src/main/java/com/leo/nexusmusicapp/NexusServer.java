package com.leo.nexusmusicapp;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NEXUS MUSIC — servidor HTTP LOCAL (127.0.0.1).
 *
 * Por que existe: o app é um WebView e o WebView não consegue tocar arquivos
 * do armazenamento do celular por `file://` (bloqueio de segurança). A solução
 * elegante é o próprio app servir o conteúdo por HTTP na interface de loopback:
 * o WebView acessa `http://127.0.0.1:<porta>/` e tudo funciona — inclusive
 * Range requests (essencial para o `<audio>` permitir avançar a faixa).
 *
 * A interface (`index.html`) é empacotada nos assets, então o app é 100%
 * autônomo: não precisa de computador, nem de rede.
 *
 * Rotas servidas (as mesmas que o servidor Python do projeto original):
 *   GET  /                     → index.html (dos assets)
 *   GET  /api/music            → biblioteca do celular (JSON)
 *   GET  /api/effects          → efeitos visuais (assets/effects)
 *   GET  /api/youtube/status   → progresso do download
 *   POST /api/youtube          → inicia o download ({"url": "..."})
 *   GET  /music/<id>           → áudio do MediaStore (com Range)
 *   GET  /cover/<id>           → capa da faixa
 *   GET  /effects/<arquivo>    → vídeo dos assets
 */
public class NexusServer {

    private static final String TAG = "NexusServer";

    private final Context ctx;
    private final Library library;
    private final YtDownload baixador;

    /**
     * Portas do servidor local — a PRIMEIRA livre é usada.
     *
     * ⚠️ **Por que fixa e não aleatória:** o `localStorage` do WebView (onde
     * ficam os FAVORITOS) é separado **por origem** — esquema + host + **porta**.
     * Com `new ServerSocket(0, …)` o SO sorteava uma porta nova a cada abertura,
     * então cada sessão do app enxergava um `localStorage` DIFERENTE e os
     * favoritos pareciam sumir. Mantendo a mesma porta, a origem é estável e os
     * favoritos persistem entre aberturas.
     */
    private static final int[] PORTAS = {8477, 8478, 8479};

    private ServerSocket socket;
    private int porta;
    private volatile boolean rodando;
    private Thread thread;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    public NexusServer(Context ctx, Library library, YtDownload baixador) {
        this.ctx = ctx;
        this.library = library;
        this.baixador = baixador;
    }

    public int getPorta() {
        return porta;
    }

    /** Sobe o servidor numa porta fixa de loopback e devolve a porta. */
    public int iniciar() throws IOException {
        IOException ultimoErro = null;

        for (int tentativa : PORTAS) {
            try {
                socket = new ServerSocket(tentativa, 16, InetAddress.getByName("127.0.0.1"));
                porta = tentativa;
                break;
            } catch (IOException e) {
                ultimoErro = e;
                Log.w(TAG, "porta " + tentativa + " indisponível: " + e.getMessage());
            }
        }

        if (socket == null) {
            throw ultimoErro != null
                    ? ultimoErro
                    : new IOException("nenhuma porta livre em 127.0.0.1");
        }

        rodando = true;

        thread = new Thread(this::aceitarLaço, "nexus-server");
        thread.setDaemon(true);
        thread.start();

        Log.i(TAG, "servidor no ar em http://127.0.0.1:" + porta);
        return porta;
    }

    public void parar() {
        rodando = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void aceitarLaço() {
        while (rodando) {
            try {
                final Socket cliente = socket.accept();
                pool.execute(() -> atender(cliente));
            } catch (IOException e) {
                if (rodando) Log.w(TAG, "aceitar: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Requisição
    // ------------------------------------------------------------------ //

    private void atender(Socket cliente) {
        try {
            cliente.setSoTimeout(30_000);

            PushbackInputStream in =
                    new PushbackInputStream(new BufferedInputStream(cliente.getInputStream(), 8192), 1);

            String linhaPedido = lerLinha(in);
            if (linhaPedido == null || linhaPedido.isEmpty()) {
                return;
            }

            String[] partes = linhaPedido.split(" ");
            String metodo = partes.length > 0 ? partes[0] : "GET";
            String alvo = partes.length > 1 ? partes[1] : "/";

            Map<String, String> cabecalhos = new HashMap<>();
            String linha;
            while ((linha = lerLinha(in)) != null && !linha.isEmpty()) {
                int doisPontos = linha.indexOf(':');
                if (doisPontos > 0) {
                    cabecalhos.put(
                            linha.substring(0, doisPontos).trim().toLowerCase(Locale.ROOT),
                            linha.substring(doisPontos + 1).trim());
                }
            }

            byte[] corpo = null;
            if ("POST".equalsIgnoreCase(metodo)) {
                int tamanho = 0;
                try {
                    tamanho = Integer.parseInt(cabecalhos.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) {
                }
                if (tamanho > 0) {
                    corpo = new byte[tamanho];
                    int lidos = 0;
                    while (lidos < tamanho) {
                        int n = in.read(corpo, lidos, tamanho - lidos);
                        if (n < 0) break;
                        lidos += n;
                    }
                }
            }

            OutputStream saida = new BufferedOutputStream(cliente.getOutputStream(), 16 * 1024);
            tratar(metodo, alvo, cabecalhos, corpo, saida);
            saida.flush();

        } catch (Exception e) {
            Log.w(TAG, "atender: " + e);
        } finally {
            try {
                cliente.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void tratar(String metodo, String alvo, Map<String, String> cabecalhos,
                        byte[] corpo, OutputStream saida) throws IOException {

        String caminho = alvo;
        int interrogar = caminho.indexOf('?');
        if (interrogar >= 0) {
            caminho = caminho.substring(0, interrogar);
        }
        caminho = java.net.URLDecoder.decode(caminho, "UTF-8");

        try {
            // ---------------------------------------------------------- //
            if (caminho.equals("/") || caminho.equals("/index.html")) {
                responderArquivo(saida, "index.html", lerAsset("index.html"), "text/html; charset=utf-8");
                return;
            }

            // ---------------------------------------------------------- //
            /* FAVORITOS: guardados no app (arquivo), não só no localStorage.
               Assim eles sobrevivem a qualquer mudança de origem/porta do
               WebView e ficam independentes do navegador. */
            if (caminho.equals("/api/favs")) {
                if ("POST".equalsIgnoreCase(metodo)) {
                    gravarFavs(corpo);
                }
                responder(saida, 200, "application/json; charset=utf-8", lerFavs());
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.equals("/api/music")) {
                responder(saida, 200, "application/json; charset=utf-8", library.comoJson());
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.equals("/api/effects")) {
                responder(saida, 200, "application/json; charset=utf-8", efeitosComoJson());
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.equals("/api/youtube/status")) {
                responder(saida, 200, "application/json; charset=utf-8", baixador.statusJson());
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.equals("/api/youtube") && "POST".equalsIgnoreCase(metodo)) {
                String corpoTexto = corpo == null ? "" : new String(corpo, StandardCharsets.UTF_8);
                String url = extrairUrl(corpoTexto);
                String json = baixador.iniciar(url);
                responder(saida, 200, "application/json; charset=utf-8", json);
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.startsWith("/music/")) {
                String id = caminho.substring("/music/".length());
                servirAudio(saida, id, cabecalhos);
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.startsWith("/cover/")) {
                String id = caminho.substring("/cover/".length());
                servirCapa(saida, id);
                return;
            }

            // ---------------------------------------------------------- //
            if (caminho.startsWith("/effects/")) {
                String nome = caminho.substring("/effects/".length());
                byte[] dados = lerAssetSilencioso("effects/" + nome);
                if (dados == null) {
                    responder(saida, 404, "text/plain", "404");
                    return;
                }
                responderArquivo(saida, nome, dados, "video/mp4");
                return;
            }

            responder(saida, 404, "text/plain; charset=utf-8", "404");
        } catch (Exception e) {
            Log.w(TAG, "tratar " + caminho + ": " + e);
            try {
                responder(saida, 500, "text/plain; charset=utf-8", "500");
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Áudio (com Range — sem isso o player não permite avançar)
    // ------------------------------------------------------------------ //

    private void servirAudio(OutputStream saida, String id, Map<String, String> cabecalhos) throws Exception {
        long total = library.tamanhoDe(id);
        if (total <= 0) {
            responder(saida, 404, "text/plain", "404");
            return;
        }

        long inicio = 0;
        long fim = total - 1;
        boolean parcial = false;

        String faixa = cabecalhos.get("range");
        if (faixa != null && faixa.startsWith("bytes=")) {
            String[] partes = faixa.substring(6).split("-");
            try {
                if (!partes[0].isEmpty()) {
                    inicio = Long.parseLong(partes[0]);
                }
                if (partes.length > 1 && !partes[1].isEmpty()) {
                    fim = Math.min(Long.parseLong(partes[1]), total - 1);
                }
                parcial = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (inicio > fim || inicio >= total) {
            inicio = 0;
            fim = total - 1;
            parcial = false;
        }

        long comprimento = fim - inicio + 1;

        StringBuilder cab = new StringBuilder();
        cab.append("HTTP/1.1 ").append(parcial ? "206 Partial Content" : "200 OK").append("\r\n");
        cab.append("Content-Type: ").append(library.mimeDe(id)).append("\r\n");
        cab.append("Accept-Ranges: bytes\r\n");
        cab.append("Content-Length: ").append(comprimento).append("\r\n");
        if (parcial) {
            cab.append("Content-Range: bytes ").append(inicio).append("-").append(fim)
                    .append("/").append(total).append("\r\n");
        }
        cab.append("Cache-Control: no-store\r\n");
        cab.append("Connection: close\r\n\r\n");
        saida.write(cab.toString().getBytes(StandardCharsets.US_ASCII));

        try (InputStream fluxo = library.abrirAudio(id)) {
            pular(fluxo, inicio);
            byte[] buffer = new byte[32 * 1024];
            long restante = comprimento;
            while (restante > 0) {
                int n = fluxo.read(buffer, 0, (int) Math.min(buffer.length, restante));
                if (n < 0) break;
                saida.write(buffer, 0, n);
                restante -= n;
            }
        }
    }

    private void servirCapa(OutputStream saida, String id) throws Exception {
        byte[] dados = library.capaDe(id);
        if (dados == null) {
            responder(saida, 404, "text/plain", "404");
            return;
        }
        responderArquivo(saida, "cover.jpg", dados, "image/jpeg");
    }

    private static void pular(InputStream fluxo, long quantidade) throws IOException {
        long resta = quantidade;
        while (resta > 0) {
            long pulado = fluxo.skip(resta);
            if (pulado <= 0) {
                if (fluxo.read() < 0) break;
                resta--;
            } else {
                resta -= pulado;
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private void responder(OutputStream saida, int codigo, String tipo, String texto) throws IOException {
        byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
        responderBytes(saida, codigo, tipo, dados);
    }

    private void responderArquivo(OutputStream saida, String nome, byte[] dados, String tipo) throws IOException {
        responderBytes(saida, 200, tipo, dados);
    }

    private void responderBytes(OutputStream saida, int codigo, String tipo, byte[] dados) throws IOException {
        StringBuilder cab = new StringBuilder();
        cab.append("HTTP/1.1 ").append(codigo).append(codigo == 200 ? " OK" : " X").append("\r\n");
        cab.append("Content-Type: ").append(tipo).append("\r\n");
        cab.append("Content-Length: ").append(dados.length).append("\r\n");
        cab.append("Cache-Control: no-store\r\n");
        cab.append("Connection: close\r\n\r\n");
        saida.write(cab.toString().getBytes(StandardCharsets.US_ASCII));
        saida.write(dados);
    }

    private static String lerLinha(InputStream in) throws IOException {
        ByteArrayOutputStream acumulado = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') acumulado.write(c);
        }
        if (c < 0 && acumulado.size() == 0) return null;
        return new String(acumulado.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] lerAsset(String nome) throws IOException {
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(nome)) {
            return lerTudo(is);
        }
    }

    private byte[] lerAssetSilencioso(String nome) {
        try {
            return lerAsset(nome);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] lerTudo(InputStream is) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = is.read(buffer)) > 0) {
            saida.write(buffer, 0, n);
        }
        return saida.toByteArray();
    }

    /**
     * Efeitos empacotados em `assets/effects`.
     *
     * ⚠️ O formato tem de ser **idêntico ao do servidor Python**, senão a
     * interface não acha nada: o JS faz `(await r.json()).effects` — ou seja,
     * precisa do OBJETO com a chave `effects`, e cada item usa `name`
     * (não `nome`), `size`, `url` e `full`. Já errei isso uma vez: mandei um
     * array direto e o player ficou sem vídeo nenhum.
     */
    private String efeitosComoJson() {
        List<String> nomes = new ArrayList<>();
        try {
            String[] lista = ctx.getAssets().list("effects");
            if (lista != null) {
                for (String n : lista) {
                    if (n.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                        nomes.add(n);
                    }
                }
            }
        } catch (IOException ignored) {
        }

        Collections.sort(nomes);

        StringBuilder json = new StringBuilder(256);
        json.append("{\"total\":").append(nomes.size()).append(",\"effects\":[");

        for (int i = 0; i < nomes.size(); i++) {
            String nome = nomes.get(i);
            String semExtensao = nome.contains(".")
                    ? nome.substring(0, nome.lastIndexOf('.'))
                    : nome;

            if (i > 0) json.append(',');
            json.append('{')
                    .append("\"name\":\"").append(escapar(semExtensao)).append("\",")
                    .append("\"size\":").append(tamanhoDoAsset("effects/" + nome)).append(',')
                    .append("\"url\":\"/effects/").append(escapar(nome)).append("\",")
                    .append("\"full\":\"/effects/").append(escapar(nome)).append("\"")
                    .append('}');
        }
        return json.append("]}").toString();
    }

    // ------------------------------------------------------------------ //
    //  Favoritos (persistidos no app)
    // ------------------------------------------------------------------ //

    private File arquivoFavs() {
        return new File(ctx.getFilesDir(), "favs.json");
    }

    /** Devolve o JSON salvo (sempre um array; "[]" se não houver nada). */
    private String lerFavs() {
        try {
            File arquivo = arquivoFavs();
            if (!arquivo.isFile()) {
                return "[]";
            }
            String texto = new String(lerTudo(new java.io.FileInputStream(arquivo)),
                    StandardCharsets.UTF_8).trim();
            /* sanidade: só aceita um array JSON */
            return texto.startsWith("[") && texto.endsWith("]") ? texto : "[]";
        } catch (Exception e) {
            Log.w(TAG, "lerFavs: " + e);
            return "[]";
        }
    }

    /** Grava o array de favoritos recebido do frontend. */
    private void gravarFavs(byte[] corpo) {
        String texto = corpo == null
                ? "[]"
                : new String(corpo, StandardCharsets.UTF_8).trim();

        if (!texto.startsWith("[") || !texto.endsWith("]")) {
            Log.w(TAG, "gravarFavs: corpo inválido, ignorado");
            return;
        }

        try (OutputStream saida = new java.io.FileOutputStream(arquivoFavs())) {
            saida.write(texto.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "gravarFavs: " + e);
        }
    }

    /** Tamanho de um asset (usa o descritor; se for comprimido, conta os bytes). */
    private long tamanhoDoAsset(String caminho) {
        try (android.content.res.AssetFileDescriptor afd = ctx.getAssets().openFd(caminho)) {
            return afd.getLength();
        } catch (Exception e) {
            try (InputStream is = ctx.getAssets().open(caminho)) {
                long total = 0;
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = is.read(buffer)) > 0) {
                    total += n;
                }
                return total;
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    /** {"url":"..."} → a url (sem depender de biblioteca de JSON). */
    static String extrairUrl(String json) {
        if (json == null) return null;
        int chave = json.indexOf("\"url\"");
        if (chave < 0) return null;
        int doisPontos = json.indexOf(':', chave);
        if (doisPontos < 0) return null;
        int primeira = json.indexOf('"', doisPontos + 1);
        if (primeira < 0) return null;
        int ultima = json.indexOf('"', primeira + 1);
        if (ultima < 0) return null;
        return json.substring(primeira + 1, ultima).replace("\\/", "/");
    }

    static String escapar(String texto) {
        if (texto == null) return "";
        StringBuilder sb = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
