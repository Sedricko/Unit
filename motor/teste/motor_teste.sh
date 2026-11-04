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
search 4321 f32
list 5
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
gotf=$(printf 'attach %s\nfrente\n' "$pid" | "$out/unitd" | tail -1)  # antes do got5, que fecha o alvo
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
confere "a tela corta o número com vírgula (4321,5 aparece como 4321): a busca acha" "${aferro#0x} f32 4321.5"
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
if grep -qE '^ok -?[0-9]+$' <<<"$gotf"; then echo "OK   frente: lê a prioridade do jogo ($gotf)"; else echo "FALHA frente: lê a prioridade do jogo ($gotf)"; falhou=1; fi
confere "ponteiro: o que não é endereço (o cabeçalho do programa) quebra o caminho" "err o caminho quebrou (não é endereço)"
confere "ponteiro: biblioteca que não existe, recusa" "err biblioteca naoexiste não está aberta"
# ===== jogo grande: cenários tirados dos testes no Orecraft (só passa para o celular se todos passarem) =====
# A: 6 níveis, campo +d38 em objeto grande, objetos colados (atalhos), libunity com ponteiros para o heap todo, e o jogo
#    trocando ponteiros enquanto a procura roda
# B: libil2cpp → tabela de classes (salto longo +8ca0) → 7 níveis, ponteiros com marca 0xb4, campo +34
# reabrir: o mesmo jogo aberto de novo com outro arranjo de memória: os atalhos quebram, o caminho de verdade tem que seguir
cc -O2 -shared -fPIC -o "$out/libil2cpp.so" motor/teste/il2cpp_teste.c
cc -O2 -shared -fPIC -o "$out/libunity.so" motor/teste/unity_teste.c
cc -O0 -pthread -o "$out/alvo_grande" motor/teste/alvo_grande.c -L"$out" -lunity -ldl -Wl,-rpath,"$out"
testa() { if eval "$2"; then echo "OK   $1"; else echo "FALHA $1"; falhou=1; fi; }
abre() {
    rm -f "$out/info_g"; "$out/alvo_grande" "$@" > "$out/info_g" & gpid=$!
    for _ in $(seq 300); do [ -s "$out/info_g" ] && break; sleep 0.1; done
    read -r gp gcobre _ _ _ _ _ _ garena gtls glnk greserva < "$out/info_g"
}
# mapa com os nomes do Android (o Linux do teste não dá nome a regiões): arena do caminho = $1, pilha e linker falsos
nomeia() {
    awk -v a="$garena-" -v t="$gtls-" -v l="$glnk-" -v n="$1" '
        index($1, a) == 1 { print $1, $2, $3, $4, $5, n; next }
        index($1, t) == 1 { print $1, $2, $3, $4, $5, "[anon:stack_and_tls:4242]"; next }
        index($1, l) == 1 { print $1, $2, $3, $4, $5, "[anon:linker_alloc]"; next }
        { print }' "/proc/$gp/maps" > "$out/maps_g"
    local esperado=3; [ "$1" = "-" ] && esperado=2  # "-": a arena não tem linha própria (cenário C: junta com a .bss)
    local alvo_nome="$1"; [ "$1" = "-" ] && alvo_nome='[anon:stack_and_tls:4242]'  # "-": só as duas de sempre
    grep -cF -e '[anon:stack_and_tls:4242]' -e '[anon:linker_alloc]' -e "$alvo_nome" "$out/maps_g" | grep -qx $esperado || { echo "FALHA teste: não renomeou as regiões"; falhou=1; }
}
raiz=$(nm -D "$out/libil2cpp.so" 2>/dev/null | awk '/ Raiz_Lixo$/ { sub(/^0+/, "", $1); print $1 }')
[ -n "$raiz" ] || raiz=$(nm "$out/libil2cpp.so" | awk '/ Raiz_Lixo$/ { sub(/^0+/, "", $1); print $1 }')
fecha() { kill -9 "$gpid" 2>/dev/null || true; wait "$gpid" 2>/dev/null || true; gpid=; }
cenario() {  # nome cenário semente fim-do-caminho-certo nome-da-arena [mexe]
    local nome=$1 certo=$4; abre "$2" "$3" ${6:-}; nomeia "$5"
    local t0; t0=$(date +%s)
    # memória livre igual à do celular do usuário no relatório (2528 MB): o limite de ponteiros fica o mesmo de lá
    gres=$(printf 'attach %s\nptrscan %s 8\n' "$gp" "$gcobre" | UNITD_MEM_MB=2500 UNITD_MAPS="$out/maps_g" "$out/unitd")
    local seg=$(( $(date +%s) - t0 ))
    grep '+' <<<"$gres" > "$out/cam_$nome" || true
    local pos; pos=$(grep -n -m1 -- "$certo\$" "$out/cam_$nome" | cut -d: -f1 || true)
    local cam; cam=$(grep -m1 -- "$certo\$" "$out/cam_$nome" || true)
    echo "$nome: $(tail -1 <<<"$gres") · ${seg} s · caminho certo na posição ${pos:-nenhuma}"
    testa "$nome: o limite de ponteiros não encheu" "! grep -q '(cheio)' <<<\"\$gres\""
    testa "$nome: acha o caminho de verdade" "[ -n '$pos' ]"
    testa "$nome: o caminho de verdade está entre os 3000 que o app guarda" "[ -n '$pos' ] && [ '$pos' -le 3000 ]"
    testa "$nome: seguir o caminho chega no valor" "[ \"\$(printf 'attach %s\nptr %s\n' $gp '$cam' | \"\$out/unitd\" | tail -1)\" = 'ok $gcobre' ]"
    testa "$nome: a libunity (gerenciador de memória) não vira caminho" "! grep -q '^libunity' '$out/cam_$nome'"
    local fora pulou; fora=$(grep -o 'fora=[0-9]*' <<<"$gres" | cut -d= -f2); pulou=$(grep -o 'pulou=[0-9]*' <<<"$gres" | cut -d= -f2)
    testa "$nome: pilha de thread e linker ignorados, como no GameGuardian (fora=${fora:-?} MB)" "[ '${fora:-0}' -ge 80 ]"
    testa "$nome: o atalho de 2 níveis pela pilha falsa não aparece" "! grep -q '^libil2cpp.so+$raiz:40:3[04]\$' '$out/cam_$nome'"
    testa "$nome: a reserva de 16 GB nunca usada foi pulada sem ler (pulou=${pulou:-?} MB)" "[ '${pulou:-0}' -ge 16000 ]"
    testa "$nome: procura em ${seg} s (até 45; o celular é umas 4× mais lento)" "[ $seg -le 45 ]"
    fecha
}
reabrir() {  # nome-de-antes cenário semente fim-do-caminho-certo
    local antes=$1 certo=$4; abre "$2" "$3"
    { echo "attach $gp"; sed 's/^/ptr /' "$out/cam_$antes"; } | "$out/unitd" | tail -n +3 > "$out/res_$antes"
    local total vivos certo_vivo
    total=$(wc -l < "$out/cam_$antes"); vivos=$(grep -c "^ok $gcobre\$" "$out/res_$antes" || true)
    certo_vivo=$(paste -d'|' "$out/cam_$antes" "$out/res_$antes" | grep -c -- "$certo|ok $gcobre\$" || true)
    echo "$antes reaberto: $vivos de $total caminhos ainda chegam no valor"
    testa "$antes reaberto: o caminho de verdade continua chegando no valor" "[ '$certo_vivo' -ge 1 ]"
    testa "$antes reaberto: a maioria dos atalhos quebrou (não enganam a conferência)" "[ $vivos -le $(( total / 2 + 1 )) ]"
    # o app, depois de conferir, fica com os vivos na ordem da lista, até 20 (RESERVA): o certo tem que estar entre eles
    local pos_vivo; pos_vivo=$(paste -d'|' "$out/cam_$antes" "$out/res_$antes" | grep "|ok $gcobre\$" | grep -n -m1 -- "$certo|" | cut -d: -f1 || true)
    testa "$antes reaberto: o caminho de verdade fica entre os 20 vivos que o app guarda (posição ${pos_vivo:-nenhuma})" "[ -n '$pos_vivo' ] && [ '$pos_vivo' -le 20 ]"
    fecha
}
cenario A a 1 ':b8:10:d38:10:38:30' '[anon:libc_malloc]' mexe
reabrir A a 2 ':b8:10:d38:10:38:30'
# controle: sem os nomes do Android, a pilha falsa é memória comum e o atalho por ela TEM que aparecer (prova que o teste vê)
abre a 5
ctl=$(printf 'attach %s\nptrscan %s 8\n' "$gp" "$gcobre" | UNITD_MEM_MB=2500 "$out/unitd")
testa "controle: sem os nomes do Android o atalho pela pilha falsa aparece (o teste enxerga o lixo)" "grep -q '^libil2cpp.so+$raiz:40:30\$' <<<\"\$ctl\""
fecha
# A2: o caminho mora numa região que o GameGuardian chama de lenta: ela entra mesmo assim (é onde o valor está)
cenario A2 a 6 ':b8:10:d38:10:38:30' '[anon:stack_and_tls:77]'
testa "A2: avisa que a região do valor entrou na procura" "grep -q 'região do valor estava fora e entrou' <<<\"\$gres\""
cenario B b 3 ':8ca0:b8:18:40:10:48:34' '[anon:scudo:primary]'
# a tabela de classes gera muito mais caminhos que cabem na lista (como num jogo de verdade, com milhares de classes):
# a procura continua e fica com os melhores. Antes ela parava ao encher, e o caminho certo podia nem ter aparecido
bach=$(grep -o 'achados=[0-9]*' <<<"$gres" | cut -d= -f2)
testa "B: a lista de candidatos encheu (${bach:-?} achados para 20000 lugares) e o caminho certo ficou" "[ '${bach:-0}' -gt 20000 ] && grep -q -- ':8ca0:b8:18:40:10:48:34\$' '$out/cam_B'"
# com milhares de caminhos, a ordem antes de reabrir não é garantida; o que vale é ficar entre os 3000 e, ao reabrir, entre os 20 vivos
bpos=$(grep -n -m1 -- ':8ca0:b8:18:40:10:48:34$' "$out/cam_B" | cut -d: -f1 || true)
testa "B: com a lista cheia, o caminho certo fica entre os 3000 que o app guarda (posição ${bpos:-nenhuma}; depois de reabrir: ver abaixo)" "[ -n '$bpos' ] && [ '$bpos' -le 3000 ]"
# D: o gerenciador do caminho é popular (5000 objetos do heap apontam para ele): o ponteiro verdadeiro é um entre 5001
cenario D d 8 ':b8:10:d38:10:38:30' '[anon:libc_malloc]'
# ===== achar pelo nome (como os scripts do GameGuardian, mas sozinho): classe do objeto + assinatura (os textos dele) =====
# 3 itens da mesma classe (Ferro, Cobre, Ouro) com o MESMO valor: só a assinatura separa. Ao reabrir, sem caminho nenhum
pelonome() {  # nome cenário semente-salvar semente-reabrir campo assinatura-esperada como-acha-a-ficha
    local nome=$1 cen=$2 campo=$5 esperada=$6 como=$7
    abre "$cen" "$3"
    local cl; cl=$(printf 'attach %s\nclasse %s\n' "$gp" "$gcobre" | "$out/unitd" | tail -n +3)
    # a assinatura: os textos ("OFF=HEX") e os números fixos ("nOFF=HEX"), como o app monta
    local ass; ass=$(awk '/^texto / { printf "%s%s=%s", (n++ ? "," : ""), $2, $3 } /^numeros / { printf "%s%s", (n++ ? "," : ""), $2 }' <<<"$cl")
    testa "$nome: ao salvar, descobre a classe do valor (ItemRecurso, campo +$campo)" "grep -qx 'ok ItemRecurso Jogo $campo' <<<\"\$cl\""
    testa "$nome: a assinatura é a esperada, sem nada dos objetos vizinhos: $ass" "[ '$ass' = '$esperada' ]"
    fecha
    abre "$cen" "$4"
    local t0 r r0; t0=$(date +%s%N)
    r=$(printf 'attach %s\npelonome ItemRecurso Jogo %s %s\n' "$gp" "$campo" "$ass" | "$out/unitd" | tail -n +3)
    local ms=$(( ($(date +%s%N) - t0) / 1000000 ))
    r0=$(printf 'attach %s\npelonome ItemRecurso Jogo %s\npelonome NaoExiste Jogo %s\n' "$gp" "$campo" "$campo" | "$out/unitd" | tail -n +3)
    echo "$nome: reaberto, pelo nome: $(tr '\n' ' ' <<<"$r")(${ms} ms) · sem assinatura: $(grep '^ok' <<<"$r0" | head -1)"
    testa "$nome: reaberto, acha o cobre só pelo nome e pela assinatura (1 objeto, no endereço novo)" "[ \"\$(head -1 <<<\"\$r\")\" = '$gcobre' ] && grep -qE '^ok 1 [0-9]+ ' <<<\"\$r\""
    testa "$nome: sem a assinatura vêm vários objetos (a assinatura é que separa)" "grep -qE '^ok ([3-9]|[1-9][0-9]+) ' <<<\"\$r0\""
    testa "$nome: acha a ficha da classe $como, olhando as 2 fichas com o mesmo nome" "grep -q '^ok 1 [0-9]* $como fichas=2' <<<\"\$r\""
    testa "$nome: classe que não existe dá erro claro" "grep -q '^err a classe NaoExiste não foi achada' <<<\"\$r0\""
    testa "$nome: até 45 s (${ms} ms; o celular é mais lento)" "[ $ms -le 45000 ]"
    # 2ª procura na mesma abertura (as tentativas de 20 s e 60 s do app): a ficha já é conhecida, não lê a memória toda de novo
    local t1 ms2; t1=$(date +%s%N)
    printf 'attach %s\npelonome ItemRecurso Jogo %s %s\npelonome ItemRecurso Jogo %s %s\n' "$gp" "$campo" "$ass" "$campo" "$ass" | "$out/unitd" > /dev/null
    ms2=$(( ($(date +%s%N) - t1) / 1000000 ))
    [ "$como" = pelo-texto ] && testa "$nome: a 2ª procura na mesma abertura lembra a ficha (2 procuras em ${ms2} ms; a 1ª sozinha levou ${ms} ms)" "[ $ms2 -lt $(( ms * 2 - ms / 4 )) ]"
    fecha
}
pelonome "nome A" a 1 2 30 '10=436f627265' pela-variavel
pelonome "nome B" b 3 4 34 '10=436f627265' pela-variavel
# E: itens sem texto (como o "Item" do Orecraft): só os números fixos (o código do item) separam os 3
pelonome "nome E" e 9 10 18 'n10=00000007,n14=00000001' pelo-texto
# F: como o "Item" do Orecraft (sem texto, sem número: só ponteiros e o valor): o nome está na definição apontada
pelonome "nome F" f 11 12 18 '10>10=436f627265' pelo-texto
# G: como no Orecraft, 2 objetos Item apontam para a definição do Cobre (o do inventário e outro, com outro número):
# o motor devolve os 2 (o app desempata pelo caminho, pelo último valor ou pelo número digitado)
abre g 13
gcl=$(printf 'attach %s\nclasse %s\n' "$gp" "$gcobre" | "$out/unitd" | tail -n +3)
gass=$(awk '/^texto / { printf "%s%s=%s", (n++ ? "," : ""), $2, $3 }' <<<"$gcl")
fecha; abre g 14
gr=$(printf 'attach %s\npelonome ItemRecurso Jogo 18 %s\n' "$gp" "$gass" | "$out/unitd" | tail -n +3)
testa "nome G: devolve os 2 objetos da mesma definição, e o cobre está entre eles" "grep -qE '^ok 2 ' <<<\"\$gr\" && grep -qx '$gcobre' <<<\"\$gr\""
fecha

# H/I: o valor numa lista de números do sistema (como no Grim Soul: "Int32" a +8a0) ou longe do começo do objeto: erro claro
for hi in h i; do
    abre $hi 15
    hr=$(printf 'attach %s\nclasse %s\n' "$gp" "$gcobre" | "$out/unitd" | tail -n +3)
    testa "nome ${hi^^}: valor fora de um objeto do jogo dá erro (não monta assinatura com os vizinhos)" "grep -qx 'err o valor não está dentro de um objeto do Unity' <<<\"\$hr\""
    fecha
done

# E: 200 mil objetos da classe do item (cabeçalhos apontando para a ficha): no Orecraft isso afogou a procura (8 min)
cenario E e 9 ':b8:10:d38:10:38:18' '[anon:libc_malloc]'
ecab=$(grep -o 'cabecalhos=[0-9]*' <<<"$gres" | cut -d= -f2)
testa "E: pulou os cabeçalhos dos objetos da classe (${ecab:-?} pulados)" "[ '${ecab:-0}' -ge 100000 ]"
testa "E: o pagemap respondeu" "grep -q 'pagemap=abriu([1-9]' <<<\"\$gres\""
# C: a memória do caminho colada logo depois da .bss da libil2cpp. Sem nome, o Linux junta as duas numa linha do mapa, e o
# motor via a arena como se fosse da biblioteca (falha que aparecia às vezes, conforme onde o sistema punha cada região)
cenario C c 7 ':b8:10:d38:10:38:30' '-'
testa "C: a arena ficou na mesma linha da .bss no mapa (a junção aconteceu de verdade)" "! grep -q '^$garena-' '$out/maps_g'"
# fim da libil2cpp na memória, pelo ELF (fim do último LOAD): nenhum caminho pode começar depois dele
libfim=0; while read -r va msz; do [ $(( va + msz )) -gt $libfim ] && libfim=$(( va + msz )); done < <(readelf -lW "$out/libil2cpp.so" | awk '$1 == "LOAD" { print $3, $6 }')
alem=0; while IFS='+:' read -r _ off _; do [ $(( 0x$off )) -ge $libfim ] && alem=$(( alem + 1 )); done < <(grep '^libil2cpp' "$out/cam_C")
testa "C: nenhum caminho começa depois do fim da libil2cpp ($alem começam)" "[ $alem -eq 0 ] && [ $libfim -gt 0 ]"
reabrir B b 4 ':8ca0:b8:18:40:10:48:34'
exit $falhou
