package com.leo.nexusmusicapp;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Biblioteca do celular: lê as músicas pelo MediaStore.
 *
 * O app NÃO tem pasta própria de músicas — ele usa o que já existe no aparelho
 * (mais os downloads que ele mesmo faz, que entram na pasta
 * `Music/NexusMusic/` e o Android indexa automaticamente).
 *
 * A mesma estrutura de 3 níveis da interface é montada aqui:
 *     GÊNERO  →  ÁLBUM  →  FAIXAS
 * onde o gênero vem da pasta do arquivo e o álbum da tag ID3.
 */
public class Library {

    private static final String TAG = "NexusLibrary";

    /** Como aparece o gênero de músicas soltas na raiz de Music/. */
    private static final String GENERO_AVULSO = "MUSICAS DO CELULAR";
    private static final String ALBUM_VAZIO = "Soltas";

    private final Context ctx;
    private final ContentResolver resolver;

    private final List<Faixa> faixas = new ArrayList<>();
    private long ultimaVarredura = 0;

    public static class Faixa {
        public String id;
        public String title;
        public String artist;
        public String album;
        public String genre;
        public long durationMs;
        public long added;
        public long albumId;
    }

    public Library(Context ctx) {
        this.ctx = ctx;
        this.resolver = ctx.getContentResolver();
    }

    public int total() {
        return faixas.size();
    }

    public long ultimaVarredura() {
        return ultimaVarredura;
    }

    // ------------------------------------------------------------------ //
    //  Varredura
    // ------------------------------------------------------------------ //

    public synchronized void recarregar() {
        List<Faixa> nova = new ArrayList<>();

        Uri colecao = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] colunas = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.IS_MUSIC,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? MediaStore.Audio.Media.RELATIVE_PATH
                        : MediaStore.Audio.Media.DATA,
        };

        String selecao = MediaStore.Audio.Media.IS_MUSIC + " != 0 OR "
                + MediaStore.Audio.Media.DURATION + " > 30000";

        try (Cursor c = resolver.query(colecao, colunas, selecao, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {

            if (c == null) {
                Log.w(TAG, "MediaStore devolveu null (permissão?)");
                return;
            }

            int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iTitulo = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArtista = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iAdd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int iCam = c.getColumnIndex(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Audio.Media.RELATIVE_PATH
                    : MediaStore.Audio.Media.DATA);

            while (c.moveToNext()) {
                Faixa f = new Faixa();
                f.id = String.valueOf(c.getLong(iId));
                f.title = limpar(c.getString(iTitulo));
                f.artist = limpar(c.getString(iArtista));
                f.album = limpar(c.getString(iAlbum));
                f.albumId = c.getLong(iAlbumId);
                f.durationMs = c.getLong(iDur);
                f.added = c.getLong(iAdd);
                f.genre = generoDoCaminho(iCam >= 0 ? c.getString(iCam) : null);

                if (f.title == null || f.title.isEmpty()) {
                    f.title = "Faixa " + f.id;
                }
                if (f.artist == null || f.artist.isEmpty()) {
                    f.artist = "Artista desconhecido";
                }
                if (f.album == null || f.album.isEmpty()) {
                    f.album = ALBUM_VAZIO;
                }

                nova.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "erro varrendo: " + e);
        }

        synchronized (this) {
            faixas.clear();
            faixas.addAll(nova);
            ultimaVarredura = System.currentTimeMillis();
        }

        Log.i(TAG, "biblioteca: " + faixas.size() + " faixas");
    }

    /**
     * O gênero sai da PASTA onde a música está (mesma lógica do servidor):
     *   Music/Rock/Album/faixa.mp3  →  "Rock"
     *   Music/faixa.mp3             →  "MUSICAS DO CELULAR"
     */
    private static String generoDoCaminho(String caminho) {
        if (caminho == null || caminho.isEmpty()) {
            return GENERO_AVULSO;
        }

        String limpo = caminho.replace('\\', '/');

        // RELATIVE_PATH vem como "Music/Rock/Album/" (com barra final);
        // DATA vem como "/storage/emulated/0/Music/Rock/Album/faixa.mp3"
        if (limpo.contains("Music/")) {
            limpo = limpo.substring(limpo.indexOf("Music/"));
        }

        String[] partes = limpo.split("/");
        List<String> pastas = new ArrayList<>();
        for (String p : partes) {
            if (p == null || p.isEmpty()) continue;
            if (p.contains(".")) continue;              // é o arquivo
            if (p.equalsIgnoreCase("Music")) continue;  // a raiz não é gênero
            if (p.equalsIgnoreCase("NexusMusic")) continue;
            pastas.add(p);
        }

        if (pastas.isEmpty()) {
            return GENERO_AVULSO;
        }
        return pastas.get(0).toUpperCase(Locale.ROOT);
    }

    private static String limpar(String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        if (t.equalsIgnoreCase("<unknown>")) return null;
        return t;
    }

    // ------------------------------------------------------------------ //
    //  Acesso aos arquivos
    // ------------------------------------------------------------------ //

    private Uri uriDe(String id) {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                Long.parseLong(id));
    }

    public long tamanhoDe(String id) {
        try (InputStream is = resolver.openInputStream(uriDe(id))) {
            if (is == null) return -1;
            /* o AssetFileDescriptor dá o tamanho sem ler o arquivo */
            try (android.content.res.AssetFileDescriptor afd =
                         resolver.openAssetFileDescriptor(uriDe(id), "r")) {
                if (afd != null && afd.getLength() > 0) {
                    return afd.getLength();
                }
            } catch (Exception ignored) {
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public InputStream abrirAudio(String id) throws Exception {
        return resolver.openInputStream(uriDe(id));
    }

    public String mimeDe(String id) {
        String tipo = resolver.getType(uriDe(id));
        return tipo != null ? tipo : "audio/mpeg";
    }

    // ------------------------------------------------------------------ //
    //  Capas
    // ------------------------------------------------------------------ //

    /**
     * Capa da faixa:
     *   1. arquivo baixado pelo app (`files/covers/<id>.jpg`) — downloads do YouTube
     *   2. capa de álbum do próprio celular (MediaStore)
     */
    public byte[] capaDe(String id) {
        File local = new File(pastaCapas(), id + ".jpg");
        if (local.isFile()) {
            try {
                return lerArquivo(local);
            } catch (Exception ignored) {
            }
        }

        long albumId = albumIdDe(id);
        if (albumId > 0) {
            try {
                Uri arte = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId);
                try (InputStream is = resolver.openInputStream(arte)) {
                    if (is != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            /* reduz para não pesar no WebView */
                            int lado = 512;
                            if (bmp.getWidth() > lado || bmp.getHeight() > lado) {
                                float escala = (float) lado / Math.max(bmp.getWidth(), bmp.getHeight());
                                bmp = Bitmap.createScaledBitmap(bmp,
                                        Math.round(bmp.getWidth() * escala),
                                        Math.round(bmp.getHeight() * escala), true);
                            }
                            ByteArrayOutputStream saida = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, saida);
                            return saida.toByteArray();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private long albumIdDe(String id) {
        synchronized (this) {
            for (Faixa f : faixas) {
                if (f.id.equals(id)) return f.albumId;
            }
        }
        return 0;
    }

    /** Pasta privada do app onde ficam as capas dos downloads. */
    public File pastaCapas() {
        File dir = new File(ctx.getFilesDir(), "covers");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Tem capa? (arquivo baixado pelo app OU capa de álbum do próprio celular) */
    private boolean temCapa(Faixa f) {
        if (new File(pastaCapas(), f.id + ".jpg").isFile()) {
            return true;
        }
        return f.albumId > 0;
    }

    private static byte[] lerArquivo(File f) throws Exception {
        try (InputStream is = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream((int) f.length());
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) > 0) {
                saida.write(buffer, 0, n);
            }
            return saida.toByteArray();
        }
    }

    // ------------------------------------------------------------------ //
    //  JSON para a interface
    // ------------------------------------------------------------------ //

    public synchronized String comoJson() {
        /* agrupa gêneros e álbuns preservando a ordem de inserção */
        Map<String, Integer> contagemGenero = new LinkedHashMap<>();
        Map<String, Integer> contagemAlbum = new LinkedHashMap<>();
        Map<String, String> albumDoGenero = new LinkedHashMap<>();
        Map<String, String> capaDoGenero = new LinkedHashMap<>();
        Map<String, String> capaDoAlbum = new LinkedHashMap<>();

        for (Faixa f : faixas) {
            contagemGenero.merge(f.genre, 1, Integer::sum);
            String chaveAlbum = f.genre + "\u0000" + f.album;
            contagemAlbum.merge(chaveAlbum, 1, Integer::sum);
            albumDoGenero.put(chaveAlbum, f.genre);

            if (!capaDoGenero.containsKey(f.genre) && temCapa(f)) {
                capaDoGenero.put(f.genre, "/cover/" + f.id);
            }
            if (!capaDoAlbum.containsKey(chaveAlbum) && temCapa(f)) {
                capaDoAlbum.put(chaveAlbum, "/cover/" + f.id);
            }
        }

        StringBuilder json = new StringBuilder(64 * 1024);
        json.append("{\"total\":").append(faixas.size()).append(",\"tracks\":[");

        for (int i = 0; i < faixas.size(); i++) {
            Faixa f = faixas.get(i);
            if (i > 0) json.append(',');
            json.append('{')
                    .append("\"title\":\"").append(NexusServer.escapar(f.title)).append("\",")
                    .append("\"artist\":\"").append(NexusServer.escapar(f.artist)).append("\",")
                    .append("\"album\":\"").append(NexusServer.escapar(f.album)).append("\",")
                    .append("\"genre\":\"").append(NexusServer.escapar(f.genre)).append("\",")
                    .append("\"url\":\"/music/").append(f.id).append("\",")
                    .append("\"cover\":\"").append(temCapa(f) ? "/cover/" + f.id : "").append("\",")
                    .append("\"duration\":").append(f.durationMs / 1000.0).append(',')
                    .append("\"added\":").append(f.added)
                    .append('}');
        }
        json.append("],\"genres\":[");

        boolean primeiro = true;
        for (Map.Entry<String, Integer> e : contagemGenero.entrySet()) {
            if (!primeiro) json.append(',');
            primeiro = false;
            json.append("{\"name\":\"").append(NexusServer.escapar(e.getKey())).append("\",")
                    .append("\"count\":").append(e.getValue()).append(',')
                    .append("\"cover\":\"")
                    .append(capaDoGenero.getOrDefault(e.getKey(), "")).append("\"}");
        }
        json.append("],\"albums\":[");

        primeiro = true;
        for (Map.Entry<String, Integer> e : contagemAlbum.entrySet()) {
            if (!primeiro) json.append(',');
            primeiro = false;
            String chave = e.getKey();
            String genero = albumDoGenero.get(chave);
            String nome = chave.contains("\u0000")
                    ? chave.substring(chave.indexOf('\u0000') + 1)
                    : chave;
            json.append("{\"name\":\"").append(NexusServer.escapar(nome)).append("\",")
                    .append("\"genre\":\"").append(NexusServer.escapar(genero)).append("\",")
                    .append("\"count\":").append(e.getValue()).append(',')
                    .append("\"cover\":\"")
                    .append(capaDoAlbum.getOrDefault(chave, "")).append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    /** Ordena as faixas por data de adição (mais recentes primeiro). */
    public void ordenarPorRecentes() {
        synchronized (this) {
            faixas.sort(Comparator.comparingLong((Faixa f) -> f.added).reversed());
        }
    }
}
