# NEXUS MUSIC OFFLINE — App Android autônomo

> **App independente**: lê as músicas **do próprio celular** e **baixa do YouTube
> direto no aparelho** — funciona com dados móveis, em qualquer rede, **sem computador
> e sem servidor**. É uma evolução do [[nexus-music]] (que precisava do PC ligado).

> ⭐ **Este é o app preferido do Leo** (16/09/2026) — o autônomo, que funciona sem
> depender do PC ligado. As evoluções devem priorizar este app; o cliente do servidor
> ([[NexusMusic]]) fica como está, para quando ele está em casa com o PC no ar.

---

## 📦 Artefato

| Item | Valor |
|---|---|

| **Nome** | `NEXUS-MUSIC-OFFLINE-v1.13.apk` |
| **Pacote** | `com.leo.nexusmusicapp` |
| **Rótulo** | **NEXUS MUSIC** |
| **Versão** | **1.13** (versionCode 14) |
| **Tamanho** | **5,4 MB** (5.637.084 bytes) |
| **SHA-256** | `a6d77607ca5e7a9cea0420e86ce6d2b4d8cfc9885ecafd3c6dff7666e669f30d` |
| **Assinatura** | Android Debug (verificada com `apksigner`) |
| **minSdk / targetSdk** | 24 (Android 7) / 34 (Android 14) |
| **Interface** | 122 KB em `assets/index.html` (a mesma do projeto web) |
| **Vídeo do player** | 🐬 `assets/effects/golfinho.mp4` — **0,89 MB, dentro do APK** (não busca do servidor) |

### Changelog do app

| Versão | Mudança |
|---|---|
| **1.13** | 🎧 **Controles na tela bloqueada, na notificação e no fone de ouvido** (MediaSession nativa em serviço de primeiro plano) — **testado e aprovado no aparelho** ✅ · assinatura v2+v3 |
| **1.12** | 🔐 Assinatura de **release** de verdade (antes era debug) + ☕ tela **♥ APOIAR** (PIX) |
| **1.9** | 🧹 **Botão ⬇ DOWNLOAD dos cards removido** — tudo já está salvo, então o botão era redundante (o painel BAIXAR do YouTube continua) |
| **1.8** | 👀 Vitrine da página principal de **4 → 8** músicas recentes (os baixados do YouTube aparecem mais; a pasta YOUTUBE continua com todos) |
| **1.7** | ⭐ **Favoritos agora persistem de verdade** — o app usa **porta fixa** (8477) e guarda os favoritos num **arquivo** (`/api/favs`), em vez de depender só do `localStorage` (que muda de 'identidade' a cada porta nova) |
| **1.6** | 🎚️ **Fader de volume 150px → 250px** (mais curso para regular) e **pasta UPLOAD removida** da navegação — as músicas que estavam lá seguem acessíveis pela busca e pelos álbuns |
| **1.5** | ★ **Botão FAVORITOS** (no lugar do UPLOAD) + **pasta FAVORITOS** na navegação, com contador e mensagem quando vazio. O upload ganhou um cartão dentro da pasta UPLOAD. **Correção importante:** o servidor passou a servir a interface com `Cache-Control: no-store` (antes o celular podia ficar preso numa versão antiga) |
| **1.4** | ✅ Vídeo do player finalmente aparecendo (o JSON de `/api/effects` tinha a forma errada) |
| **1.3** | 🎵 **"Agora tocando" fixo no topo** — o player tem 220px e rolava junto com a página, então ao descer para escolher a música o nome desaparecia. Agora um mini-player **aparece só quando o player sai de vista**, com o nome da faixa, artista e play/pause; tocando nele volta ao player |
| **1.2** | 🐬 Vídeo do player dentro do app (golfinho, 0,89 MB) — não depende do servidor |
| **1.1** | ⚡ Download 5,7× mais rápido (5 conexões em paralelo) + velocidade em MB/s |
| 1.0 | Versão inicial |

**Não depende de IP, rede local nem servidor.** Basta internet para baixar.

---

## ⚙️ Como funciona (a arquitetura)

O WebView do Android **não consegue** tocar arquivos do armazenamento por `file://`
(bloqueio de segurança). Então o app faz algo elegante: **roda um servidor HTTP dentro
de si mesmo** e o WebView carrega `http://127.0.0.1:<porta>/`.

```
┌─────────────────────── APP NEXUS MUSIC ────────────────────────┐
│                                                                │
│   MainActivity ──► WebView ──► http://127.0.0.1:PORTA/         │
│                                     ▲                          │
│                                     │ HTTP local (loopback)    │
│                          ┌──────────┴──────────┐               │
│                          │   NexusServer       │               │
│                          │   (ServerSocket)    │               │
│                          └──┬──────────────┬───┘               │
│                             ▼              ▼                    │
│                      Library.java      YtDownload.java          │
│                      (MediaStore)      (NewPipe Extractor)      │
│                             │              │                    │
│                    músicas do celular   internet ──► YouTube    │
│                    capas de álbum                               │
└────────────────────────────────────────────────────────────────┘
```

**Classes:**

| Arquivo | Papel |
|---|---|
| `MainActivity.java` | permissões, WebView, back nativo, download de arquivos |
| `NexusServer.java` | servidor HTTP local (rotas, JSON, **Range requests** para o áudio) |
| `Library.java` | biblioteca do celular via MediaStore (gênero = pasta, álbum = tag) |
| `YtDownload.java` | download do YouTube via **NewPipe Extractor** + gravação no MediaStore |

**Rotas servidas (idênticas às do servidor Python):**

```
GET  /                    → a interface (assets)
GET  /api/music           → biblioteca do celular (JSON)
GET  /api/effects         → efeitos visuais (assets/effects, opcional)
GET  /api/youtube/status  → progresso do download
POST /api/youtube         → {"url": "..."} inicia o download
GET  /music/<id>          → áudio do MediaStore (com Range)
GET  /cover/<id>          → capa da faixa
```

---

## ✨ O que funciona

| Recurso | Como se comporta |
|---|---|
| **Biblioteca do celular** | lê TODAS as músicas do aparelho (MediaStore) |
| **Download do YouTube** | 🔥 **funciona com dados móveis**, em qualquer rede |
| **Navegação por pastas** | gênero → álbum → faixas, com swipe para voltar |
| **Voltar até a home** | corrigido (a entrada inicial do histórico é a página inicial) |
| **Capa das músicas** | capa de álbum do celular + a thumb do YouTube nos downloads |
| **Botão ⬇ DOWNLOAD** | salva em `Downloads/NexusMusic/` (gerenciador do Android) |
| **Favoritos, busca, player** | iguais aos do projeto web |
| **Onde as músicas ficam** | downloads em `Music/NexusMusic/YOUTUBE/` (aparecem para outros players) |

### Limitações honestas

| Limite | Detalhe |
|---|---|
| **Sem upload** | o botão UPLOAD do web não existe aqui (as músicas já estão no celular) |
| **Um só vídeo de efeito** | só o 🐬 **golfinho** (0,89 MB) — escolha do usuário para o app não pesar. Os 9 minis que haviam foram movidos para `EFEITOS/_reserva/` (nada foi apagado) |
| **Áudio em M4A** | o download salva **M4A/WebM** (o Android toca nativamente) — **não converte para MP3** porque o `ffmpeg-kit` foi arquivado e não tem manutenção |
| **Atualização manual** | quando o YouTube mudar, o app para de baixar e precisa de **nova versão** (o extrator é embutido) |

---

## 📲 Como instalar e usar

1. Copie o APK para o celular e instale (permita "fontes desconhecidas")
2. **Na primeira abertura**, autorize:
   - 🎵 **"Músicas e áudio"** → para ler a biblioteca
   - 🔔 **Notificações** → para avisar quando o download terminar
3. Pronto! A biblioteca do celular já aparece na tela
4. Para baixar: cole o link do YouTube no painel e toque em **⬇ BAIXAR**

> 💡 O app sincroniza a biblioteca **toda vez que volta ao primeiro plano**, então
> músicas novas (baixadas ou copiadas) aparecem sozinhas.

---

## 🔧 Como recompilar / atualizar

```bash
cd "/home/leo/Documents/Obsidian Vault/01_Projeto/APK/NexusMusicApp"
export ANDROID_HOME=/home/leo/Android/Sdk
./gradlew assembleDebug --no-daemon
```

### ⚠️ Quando o YouTube parar de funcionar

O YouTube muda com frequência. O extrator é embutido, então **o app precisa de uma
versão nova**. O conserto é subir a versão do extrator:

```groovy
// app/build.gradle
implementation 'com.github.teamnewpipe:NewPipeExtractor:v0.26.5'   // ← subir aqui
```

Versões novas: <https://github.com/TeamNewPipe/NewPipeExtractor/releases>

Depois é só recompilar e reinstalar (os dados são mantidos).

### Atualizar a interface

O `assets/index.html` é uma cópia do projeto web
([[nexus-music]]). Para levar mudanças de lá:

```bash
cp "../../HTML/Nexus_Music/index.html" app/src/main/assets/index.html
./gradlew assembleDebug --no-daemon
```

*(A única diferença intencional é o texto do painel: "baixa o áudio direto no celular",
porque aqui o download é M4A, não MP3.)*

---

## ✅ Verificações feitas no build

| Teste | Resultado |
|---|---|
| `BUILD SUCCESSFUL` | ✅ |
| `apksigner verify` | ✅ assinatura debug válida |
| `aapt dump badging` | ✅ `com.leo.nexusmusicapp` · label **NEXUS MUSIC** |
| Permissões | ✅ INTERNET · READ_MEDIA_AUDIO · READ/WRITE_EXTERNAL_STORAGE · POST_NOTIFICATIONS |
| `assets/index.html` empacotado | ✅ **hash idêntico** ao do projeto |
| JS do asset (`node --check`) | ✅ sem erros |
| Classes do app no dex | ✅ 16 classes (`classes3.dex`) |
| NewPipe Extractor no dex | ✅ 760 classes (`classes.dex`) |

> ⚠️ **Ainda não testado em execução** — não há emulador Android instalado nesta máquina.
> O teste de runtime é o do celular (instalar, dar as permissões e baixar um link).

---

## 🧩 Diferença entre os dois apps

| | [[NexusMusic]] (cliente) | **Este (offline)** |
|---|---|---|
| Precisa do PC ligado | ❌ sim | ✅ **não** |
| Baixa do YouTube | ✅ pelo servidor (yt-dlp, sempre atualizado) | ✅ no app (NewPipe, precisa recompilar quando quebra) |
| Biblioteca | a do PC (2,9 GB) | a do **celular** |
| Tamanho | 2,9 MB | 4,5 MB |
| Melhor para | casa, na rede | **qualquer lugar** |

💡 **Os dois convivem**: o app do servidor tem o acervo grande de casa; este funciona
na rua. Os pacotes são diferentes, então podem ficar instalados juntos.

---

## 🔗 Notas relacionadas

- [[nexus-music]] — o servidor e a interface (web)
- [[nexus-music-app-offline-build]] — memória do build (decisões técnicas e armadilhas)
- [[arquitetura]] · [[historico]] · [[nexus-music-dicas]] · [[nexus-music-solucoes]]


## 🌍 Código aberto (publicado em 16/09/2026)

**https://github.com/leo-venom/nexus-music** — repositório **público**, licença **GPL-3.0**.

| Item | Valor |
|---|---|
| Comando de publicação | `./scripts/publicar.sh` (cria o repo, faz o push e publica a Release com o APK) |
| Arquivos versionados | **26** |
| Commits iniciais | `dc0255c` (código) · `3b3cb45` (script de publicação) |
| Release | **v1.9** com `NEXUS-MUSIC-OFFLINE-v1.13.apk` (5,3 MB) anexado |
| Linguagens (GitHub) | HTML 66,6% · Java 34,1% |
| Créditos | NewPipe Extractor (GPL-3.0) e yt-dlp (Unlicense) |

### ⚖️ Por que GPL-3.0 (e não MIT)

O **NewPipe Extractor é GPL-3.0** (copyleft). Quem distribui um app que o incorpora **precisa**
licenciar o app em GPL-3.0 também — foi verificado na API do GitHub antes de escolher. Não é
limitação: é o mesmo caminho do próprio NewPipe, e garante que o crédito ao autor permaneça em
qualquer fork.

### 🚫 O que NÃO foi versionado (e por quê)

`local.properties` (caminho do SDK), `.gradle/`, `build/`, APKs e keystores — todos no `.gitignore`.
Os binários vão por **Releases**, não pelo git.

### 📢 Decisão sobre monetização

Ver [[nexus-music-decisao-monetizacao]]: **não monetizar** o download do YouTube (viola os Termos de
Serviço e é barrado pela política da Play Store). Publicar como código aberto foi o caminho escolhido
para reconhecimento.

## 💡 Evoluções possíveis (ideias guardadas — nenhuma pedida ainda)

Anotadas em 16/09/2026, quando o Leo disse que prefere este app. **Nada disso foi aprovado** —
ficam como sugestões para quando ele quiser:

1. **Download em segundo plano + notificação** — continuar baixando com o app fechado/minimizado e
   avisar pelo sistema quando terminar (hoje o trabalho vive na Activity);
2. **Receber link compartilhado** — registrar `intent-filter` para `youtube.com`/`youtu.be`, assim
   compartilhar de outro app abre o NEXUS já com o link no painel;
3. **Fila de downloads** — colar vários links e baixar em sequência;
4. **Playlists próprias** — listas nomeadas, além de FAVORITOS;
5. **Sleep timer** — parar a reprodução depois de X minutos;
6. **Mais efeitos no player** — hoje só o 🐬 golfinho (os outros 18 estão em `EFEITOS/_reserva/`).

*Build de 16/09/2026 · By Leandro*
