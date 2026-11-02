#!/usr/bin/env bash
# Testa o unitd no Linux contra um processo de verdade (alvo.c). Precisa de root (ou ptrace liberado): sudo.
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d); tpid=
gpid=
trap 'kill -9 $tpid $gpid 2>/dev/null || true; rm -rf "$out"' EXIT
cc -O2 -Wall -Werror -pthread -o "$out/unitd" motor/unitd.c -lm
cc -O0 -o "$out/alvo" motor/teste/alvo.c
"$out/alvo" > "$out/info" & tpid=$!
for _ in $(seq 50); do [ -s "$out/info" ] && break; sleep 0.1; done
read -r pid aouro avida axp atempo arec acobre aferro < "$out/info"
falhou=0
confere() { if grep -qF -- "$2" <<<"$got"; then echo "OK   $1"; else echo "FALHA $1 (esperava: $2)"; falhou=1; fi; }

# uma sessão só (o congelador vive enquanto o unitd vive): o sleep deixa ele reescrever o 555 depois do write 1
got=$({ cat <<CMDS
search 1
attach $pid
search 123456789;87.5
list 5
search 87~88 f32
list 50
tirar $avida
list 50
search 123456789
write $aouro i32 987654321
refine 987654321
list 5
read $aouro i32
search 87.5
list 50
write $avida f32 99.25
read $avida f32
search 123456780~123456800 i32
search 9876543210 i64
search 4321 f64
freeze $aouro i32 555
write $aouro i32 1
sleep 400
read $aouro i32
unfreeze all
bytes $axp 16
CMDS
} | "$out/unitd")
echo "$got"
echo "----"
# assinatura: o desenho de 16 bytes do xp (com os 8 do valor como ??) acha o xp de novo
hexxp=$(grep -A0 -m1 -E '^ok [0-9a-f]{32}$' <<<"$got" | cut -c4-)
got2=$(printf 'attach %s\naob %s\naob %s\nbytes %s 5000\n' "$pid" "${hexxp:0:16}????????????????" "$hexxp" "$axp" | "$out/unitd")
echo "$got2"
# busca por mudança: foto, o valor muda (10) e volta (0)
got3=$({ cat <<CMDS
attach $pid
diff mudou
snap $out/foto
write $arec f32 10
diff mudou
write $arec f32 0
diff foto
list 50
CMDS
} | "$out/unitd")
echo "$got3"
# ponteiro: do global "jogo" (fixo, no próprio programa) até o cobre no heap; o caminho achado leva ao mesmo endereço
got4=$(printf 'attach %s\nptrscan %s 3\nptrscan %s 3\n' "$pid" "$acobre" "$aferro" | "$out/unitd")
echo "$got4"
cam=$(grep -m1 '^alvo+' <<<"$got4" || true)
got5=$(printf 'attach %s\nptr %s\nptr naoexiste+10:8\nptr alvo+0:8\ninfo\nandroid 1;rm x\nandroid 100.5 com.jogo\nvivo\nfechar\nsleep 200\nvivo\n' "$pid" "$cam" | "$out/unitd")  # fechar por último: mata o alvo
echo "$got5"
got="$got
$got2
$got3
$got4
$got5"
got="$got
ordem: $(tr '\n' ' ' <<<"$got2")"  # numa linha só, para conferir a ordem das respostas
confere "sem jogo, recusa" "err escolha o jogo primeiro"
confere "attach" "ok $pid"
confere "acha o ouro (Dword)" "$aouro i32 987654321"
confere "lê o que escreveu" "ok 987654321"
confere "acha a vida (Float)" "$avida f32 87.5"
confere "escreve Float" "ok 99.25"
confere "acha Qword" "ok 1"
confere "Double arredondado da tela (4321 acha 4321.25)" "ok 1"
confere "congelar segura o valor" "ok 555"
confere "bytes: 16 bytes do xp em hex" "ok ea16b04c02000000"
confere "aob acha o xp pelo desenho" "$axp"
confere "aob: o endereço vem antes do ok (o app para de ler no ok)" "$axp ok 1"
confere "bytes recusa mais de 4096" "err quantidade de 1 a 4096"
confere "faixa Float 87~88 acha a vida 87.5" "$avida f32"
confere "tirar: a vida sai dos resultados" "ok 0 1"
confere "grupo 123456789;87.5 acha o ouro" "$aouro i32 123456789"
confere "grupo 123456789;87.5 acha a vida junto" "$avida f32 87.5"
confere "mudança: sem foto, recusa" "err tire a foto primeiro"
confere "mudança: mudou e voltou ao da foto acha o valor" "$arec f32 0 0"
confere "ponteiro: acha um caminho a partir do programa" "alvo+"
confere "ponteiro: o caminho termina em +40 e +8" ":40:8"
confere "ponteiro: seguir o caminho chega no cobre" "ok $acobre"
confere "ponteiro: salto longo da biblioteca para a tabela (Unity), item 700" ":15e0:30"
if grep -qE '^ok [1-9][0-9]* [0-9]+ [1-9][0-9]* [1-9][0-9]*$' <<<"$got5"; then echo "OK   info: regiões e MB do jogo"; else echo "FALHA info: regiões e MB do jogo"; falhou=1; fi
confere "android: recusa texto que iria para o sistema" "err uso: android SEGUNDOS PACOTE"
confere "fechar: depois de fechar, o motor avisa" "err o jogo fechou"
confere "ponteiro: o que não é endereço (o cabeçalho do programa) quebra o caminho" "err o caminho quebrou (não é endereço)"
confere "ponteiro: biblioteca que não existe, recusa" "err biblioteca naoexiste não está aberta"
# jogo grande, como o Orecraft: libil2cpp, 16 milhões de ponteiros em 2 GB (o dobro da densidade do Orecraft) (passa do limite do motor), objetos do caminho colados
# (atalhos de deslocamento grande que quebram ao reabrir) e outros objetos apontando para cada passo. O caminho de verdade
# tem 6 níveis: libil2cpp → classe +b8 → estáticos +10 → gerenciador +d38 → lista +10 → itens +38 → item +30 = cobre
cc -O2 -shared -fPIC -o "$out/libil2cpp.so" motor/teste/il2cpp_teste.c
cc -O2 -shared -fPIC -o "$out/libunity.so" motor/teste/unity_teste.c
cc -O0 -o "$out/alvo_grande" motor/teste/alvo_grande.c -L"$out" -lil2cpp -lunity -Wl,-rpath,"$out"
"$out/alvo_grande" > "$out/info_g" & gpid=$!
for _ in $(seq 200); do [ -s "$out/info_g" ] && break; sleep 0.1; done
read -r gp gcobre _ < "$out/info_g"
t0=$(date +%s)
# memória livre igual à do celular do usuário no relatório (2528 MB livres): o limite de ponteiros fica o mesmo de lá
got6=$(printf 'attach %s\nptrscan %s 8\n' "$gp" "$gcobre" | UNITD_MEM_MB=2500 "$out/unitd")
seg=$(( $(date +%s) - t0 ))
tail -1 <<<"$got6"; echo "jogo grande: ${seg} s"
camg=$(grep -m1 '^libil2cpp.so+[0-9a-f]*:b8:10:d38:10:38:30$' <<<"$got6" || true)
got7=$(printf 'attach %s\nptr %s\n' "$gp" "$camg" | "$out/unitd")
got="$got6
$got7"
if grep -q '(cheio)' <<<"$got6"; then echo "FALHA jogo grande: o limite de ponteiros encheu"; falhou=1; else echo "OK   jogo grande: com a memória do celular, cabem os 16 milhões de ponteiros"; fi
confere "jogo grande: acha o caminho de verdade de 6 níveis do Unity" ":b8:10:d38:10:38:30"
got="$(grep -m1 '^libil2cpp' <<<"$got6")"
confere "jogo grande: o caminho de verdade vem primeiro (na frente dos atalhos)" ":b8:10:d38:10:38:30"
got="$got6
$got7"
confere "jogo grande: seguir o caminho chega no cobre" "ok $gcobre"
if grep -q '^libunity' <<<"$got6"; then echo "FALHA jogo grande: caminhos da libunity (gerenciador de memória) não deviam sair"; falhou=1; else echo "OK   jogo grande: a libunity (gerenciador de memória) não vira caminho"; fi
if [ "$seg" -le 60 ]; then echo "OK   jogo grande: procura em ${seg} s (até 60)"; else echo "FALHA jogo grande: procura levou ${seg} s"; falhou=1; fi
exit $falhou
