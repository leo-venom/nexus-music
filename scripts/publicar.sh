#!/usr/bin/env bash
#
# Publica o NEXUS MUSIC no GitHub.
#
# Use DEPOIS de autenticar o CLI (uma vez só):
#     gh auth login
#
# Depois:
#     ./scripts/publicar.sh              # cria "nexus-music"
#     ./scripts/publicar.sh outro-nome   # ou escolha outro nome
#
set -euo pipefail

cd "$(dirname "$0")/.."

REPO="${1:-nexus-music}"
DESC="Offline-first Android music player with a retro-cyberpunk interface"
APK="NEXUS-MUSIC-OFFLINE-v1.9.apk"

if ! gh auth status >/dev/null 2>&1; then
    echo "❌ O gh CLI ainda não está autenticado."
    echo "   Rode primeiro:  gh auth login"
    exit 1
fi

USUARIO="$(gh api user -q .login)"
echo "→ autenticado como: $USUARIO"
echo "→ repositório: $USUARIO/$REPO"

# 1) cria o repositório (se não existir) e envia o código
if gh repo view "$USUARIO/$REPO" >/dev/null 2>&1; then
    echo "→ o repositório já existe, apenas enviando o código…"
    git remote remove origin 2>/dev/null || true
    git remote add origin "https://github.com/$USUARIO/$REPO.git"
    git push -u origin main
else
    gh repo create "$REPO" --public --source=. --push --description "$DESC"
fi

# 2) publica o APK como Release (o .gitignore mantém binários fora do git)
if [ -f "$APK" ]; then
    if gh release view v1.9 >/dev/null 2>&1; then
        echo "→ a release v1.9 já existe, pulando"
    else
        echo "→ criando a release v1.9 com o APK…"
        gh release create v1.9 "$APK" \
            --title "v1.9 — favoritos que persistem, vitrine com 8 músicas" \
            --notes "Primeira versão pública.

**Destaques**
- Biblioteca do celular (MediaStore), sem importação
- Download do YouTube no próprio aparelho (5 conexões paralelas)
- Favoritos gravados em arquivo — sobrevivem a reinícios
- Interface cyber/retrô: controles analógicos, fader, tela de efeitos

**Instalação:** baixe o APK abaixo e instale (é preciso permitir fontes desconhecidas).

⚠️ Uso pessoal/educacional. Baixar do YouTube pode violar os Termos de Serviço — use apenas com conteúdo que você tem direito."
    fi
else
    echo "⚠️  APK não encontrado ($APK) — a release foi pulada."
    echo "    Compile com: ./gradlew assembleDebug e copie o APK para esta pasta."
fi

echo
echo "✅ pronto: https://github.com/$USUARIO/$REPO"
