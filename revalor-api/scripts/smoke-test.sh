#!/usr/bin/env bash
# Teste de ponta a ponta da API (precisa de bash, curl e jq). Uso: ./scripts/smoke-test.sh [http://localhost:8080]
# Fluxo: Rafael registra o bagaço de malte -> qualidade libera -> Rafael oferta -> Beatriz propõe
# -> Rafael contrapropõe -> Beatriz aceita -> entrega percorre o trajeto até a confirmação.
set -euo pipefail
B="${1:-http://localhost:8080}"
J='Content-Type: application/json'
falhas=0
ok()   { echo "  ok   $1"; }
erro() { echo "  FALHOU $1 (esperado: $2, obtido: $3)"; falhas=$((falhas+1)); }
confere() { [ "$2" = "$3" ] && ok "$1" || erro "$1" "$2" "$3"; }
post() { curl -sS -X POST "$B$1" -H "$J" -d "$2"; }
codigo() { curl -sS -o /dev/null -w '%{http_code}' -X POST "$B$1" -H "$J" -d "$2"; }

echo "1) Rafael registra o lote e a qualidade libera"
LOTE=$(post /api/lotes '{"materiaPrima":"Bagaço de malte","categoria":"Grãos e cereais","linhaOrigem":"Brassagem","setorOrigem":"Filtração","loteProducaoOrigem":"BRASS-0710-01","quantidadeLiquidaKg":600,"motivo":"Subproduto da brassagem","condicaoObservada":"Úmido, resfriado","temperaturaColeta":4,"acondicionamento":"Big bag forrado","localArmazenamento":"CF-01 • B-02","coletadoEm":"2026-10-07T08:30:00","limiteUsoInformado":"2026-10-07T14:30:00","responsavel":"Rafael Medeiros"}' | jq -r .resumo.codigo)
echo "     lote $LOTE"
confere "lote nasce em quarentena" QUARENTENA "$(curl -sS $B/api/lotes/$LOTE | jq -r .resumo.situacao)"
confere "quarentena não pode ser ofertada (422)" 422 "$(codigo /api/lotes/$LOTE/oferta '{"precoKg":2.5,"quantidadeKg":600,"responsavel":"Rafael"}')"
post /api/lotes/$LOTE/liberacao '{"responsavel":"Marina Costa","laudo":"Q-MALTE-01","processoCodigo":"UP-08","limiteUsoValidado":"2026-10-07T14:30:00","itens":[{"criterio":"Identidade e integridade","conforme":true,"observacao":"ok"},{"criterio":"Histórico de temperatura","conforme":true,"observacao":"ok"}]}' >/dev/null
confere "lote liberado" DISPONIVEL "$(curl -sS $B/api/lotes/$LOTE | jq -r .resumo.situacao)"

echo "2) Rafael oferta no marketplace; Beatriz vê a vitrine"
confere "oferta criada (201)" 201 "$(codigo /api/lotes/$LOTE/oferta '{"precoKg":2.50,"quantidadeKg":600,"quantidadeMinimaKg":100,"responsavel":"Rafael Medeiros"}')"
confere "vitrine mostra o lote" 1 "$(curl -sSG --data-urlencode "busca=bagaço" "$B/api/mercado/ofertas" | jq "[.[]|select(.loteCodigo==\"$LOTE\")]|length")"
confere "rastreabilidade acompanha a oferta" true "$(curl -sS $B/api/mercado/ofertas/$LOTE | jq '.rastreabilidade|length>=3')"

echo "3) Negociação"
confere "quantidade abaixo do mínimo (422)" 422 "$(codigo /api/mercado/ofertas/$LOTE/negociacoes '{"compradorCodigo":"COMP-01","quantidadeKg":50,"precoKg":2.0}')"
NEG=$(post /api/mercado/ofertas/$LOTE/negociacoes '{"compradorCodigo":"COMP-01","quantidadeKg":400,"precoKg":2.00,"mensagem":"Pedido semanal"}' | jq -r .codigo)
confere "vez do vendedor" VENDEDOR "$(curl -sS $B/api/negociacoes/$NEG | jq -r .vezDe)"
confere "comprador fora da vez (422)" 422 "$(codigo /api/negociacoes/$NEG/aceite '{"autor":"COMPRADOR"}')"
post /api/negociacoes/$NEG/contraproposta '{"autor":"VENDEDOR","quantidadeKg":400,"precoKg":2.30,"mensagem":"Melhor que isso não fecha"}' >/dev/null
confere "vez do comprador" COMPRADOR "$(curl -sS $B/api/negociacoes/$NEG | jq -r .vezDe)"
ENT=$(post /api/negociacoes/$NEG/aceite '{"autor":"COMPRADOR","mensagem":"Fechado"}' | jq -r .entregaCodigo)
confere "negociação aceita" ACEITA "$(curl -sS $B/api/negociacoes/$NEG | jq -r .status)"
confere "total da venda (400 kg x R\$ 2,30)" 920.00 "$(curl -sS $B/api/negociacoes/$NEG | jq '.valores.totalBruto')"
confere "comissão Revalor 15%" 138.00 "$(curl -sS $B/api/negociacoes/$NEG | jq '.valores.comissaoRevalor')"
confere "saldo reservado no lote" 200 "$(curl -sS $B/api/lotes/$LOTE | jq '.resumo.disponivelKg')"

echo "4) Logística em tempo real ($ENT)"
confere "entrega aguardando coleta" AGUARDANDO_COLETA "$(curl -sS $B/api/entregas/$ENT | jq -r .status)"
post /api/entregas/$ENT/coleta '{}' >/dev/null
for i in 1 2 3; do post /api/entregas/$ENT/avanco '' >/dev/null; done
confere "em trânsito" EM_TRANSITO "$(curl -sS $B/api/entregas/$ENT | jq -r .status)"
POS=$(curl -sS $B/api/entregas/$ENT)
LAT=$(echo "$POS" | jq .posicao.latitude); LNG=$(echo "$POS" | jq .posicao.longitude)
ALERTA=$(post /api/entregas/$ENT/telemetria "{\"latitude\":$LAT,\"longitude\":$LNG,\"temperaturaC\":14.0}" | jq .alertaTemperatura)
confere "telemetria quente gera alerta" true "$ALERTA"
for i in $(seq 1 15); do
  s=$(curl -sS $B/api/entregas/$ENT | jq -r .status); [ "$s" = ENTREGUE ] && break
  post /api/entregas/$ENT/avanco '' >/dev/null
done
FIM=$(curl -sS $B/api/entregas/$ENT)
confere "entrega concluída" ENTREGUE "$(echo "$FIM" | jq -r .status)"
confere "progresso 100%" 100 "$(echo "$FIM" | jq .progresso)"
confere "rastreabilidade registra a entrega" true "$(curl -sS $B/api/lotes/$LOTE | jq '[.rastreabilidade[].titulo]|index("Entregue ao comprador")!=null')"

echo; [ $falhas -eq 0 ] && echo "TUDO OK" || { echo "$falhas verificação(ões) falharam"; exit 1; }
