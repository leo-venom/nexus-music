#!/usr/bin/env bash
#
# Sincroniza a interface (web) para os assets do APK e VALIDA o JS.
#
# Por que existe: o Gradle NÃO valida JavaScript. Um erro de sintaxe no
# index.html passa pelo build e só aparece quando o app abre em branco.
# Este script torna obrigatório o `node --check` a cada sincronização.
#
set -euo pipefail

PROJ="/home/leo/Documents/Obsidian Vault/01_Projeto"
ORIGEM="$PROJ/HTML/Nexus_Music/index.html"
DESTINO="$PROJ/APK/NexusMusicApp/app/src/main/assets/index.html"

python3 - "$ORIGEM" "$DESTINO" <<'PY'
import io, pathlib, sys

origem, destino = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
s = io.open(origem, encoding="utf-8").read()

# única diferença intencional entre web e app: no app o áudio sai em M4A
s = s.replace(
    "cola o link e vira MP3 na pasta YOUTUBE",
    "cola o link e baixa o áudio direto no celular",
)
io.open(destino, "w", encoding="utf-8").write(s)
print("  interface copiada para os assets ✅")
PY

echo "  validando o JS do asset (o Gradle não faz isso!)…"

if python3 - "$DESTINO" <<'PY'
import io, pathlib, subprocess, sys

s = io.open(pathlib.Path(sys.argv[1]), encoding="utf-8").read()
i = s.rindex("<script>") + 8
j = s.index("</script>", i)
io.open("/tmp/_sync_check.js", "w", encoding="utf-8").write(s[i:j])

r = subprocess.run(["node", "--check", "/tmp/_sync_check.js"], capture_output=True, text=True)
if r.returncode != 0:
    print("  ❌ ERRO DE SINTAXE NO JS — o build seria gerado com o app quebrado:")
    print(r.stderr[:600])
    sys.exit(1)

css = s[s.index("<style>") + 7:s.index("</style>")]
if css.count("{") != css.count("}"):
    print(f"  ❌ CSS desbalanceado: {css.count('{')} / {css.count('}')}")
    sys.exit(1)

print(f"  ✅ JS válido · CSS {css.count('{')}/{css.count('}')}")
PY
then
    echo "  ✅ sincronização segura concluída — pode buildar"
else
    echo "  ⚠️  corrija o erro acima antes de buildar (ou restaure o backup)"
    exit 1
fi
