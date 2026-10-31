#!/usr/bin/env bash
# Testa o unitd no Linux contra um processo de verdade (alvo.c). Precisa de root (ou ptrace liberado): sudo.
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d); tpid=
trap 'kill -9 $tpid 2>/dev/null || true; rm -rf "$out"' EXIT
cc -O2 -Wall -Werror -pthread -o "$out/unitd" motor/unitd.c -lm
cc -O0 -o "$out/alvo" motor/teste/alvo.c
"$out/alvo" > "$out/info" & tpid=$!
for _ in $(seq 50); do [ -s "$out/info" ] && break; sleep 0.1; done
read -r pid aouro avida axp atempo < "$out/info"
falhou=0
confere() { if grep -qF -- "$2" <<<"$got"; then echo "OK   $1"; else echo "FALHA $1 (esperava: $2)"; falhou=1; fi; }

# uma sessão só (o congelador vive enquanto o unitd vive): o sleep deixa ele reescrever o 555 depois do write 1
got=$({ cat <<CMDS
search 1
attach $pid
search 123456789
write $aouro i32 987654321
refine 987654321
list 5
read $aouro i32
search 87.5
list 50
write $avida f32 99.25
read $avida f32
search 87~88 f32
search 123456780~123456800 i32
search 9876543210 i64
search 4321 f64
freeze $aouro i32 555
write $aouro i32 1
sleep 400
read $aouro i32
unfreeze all
CMDS
} | "$out/unitd")
echo "$got"
echo "----"
confere "sem jogo, recusa" "err escolha o jogo primeiro"
confere "attach" "ok $pid"
confere "acha o ouro (Dword)" "$aouro i32 987654321"
confere "lê o que escreveu" "ok 987654321"
confere "acha a vida (Float)" "$avida f32 87.5"
confere "escreve Float" "ok 99.25"
confere "acha Qword" "ok 1"
confere "Double arredondado da tela (4321 acha 4321.25)" "ok 1"
confere "congelar segura o valor" "ok 555"
confere "faixa Float 87~88 acha a vida 87.5" "$avida f32"
exit $falhou
