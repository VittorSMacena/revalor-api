# Revalor API (Java 17 + Spring Boot 3)

Back-end e tela de demonstração do MVP da Revalor: o lado do **gerador** (Rafael), o lado do **comprador**
(Beatriz) e a **sincronização logística em tempo real**. Dados em memória, carga demonstrativa de
07/10/2026 10:00 (Campinas • Turno A).

    mvn spring-boot:run
    # abra http://localhost:8080  (a tela está em src/main/resources/static/index.html)

Em outro terminal, o teste de ponta a ponta (precisa de bash, curl e jq):

    ./scripts/smoke-test.sh            # ou ./scripts/smoke-test.sh http://localhost:8080
    mvn test                           # testes de regra de negócio (sem subir o Spring)

## Roteiro do Demoday (3 minutos)

1. Abra duas janelas lado a lado em `http://localhost:8080`: uma em **Rafael • Gerador**, outra em **Beatriz • Compradora**.
2. Rafael: **Preparar cenário** (registra o bagaço de malte, a qualidade libera e o lote é ofertado a R$ 2,50/kg).
   Para mostrar passo a passo, use **Registrar excedente** → **Liberar (qualidade)** → **Ofertar no marketplace**.
3. Beatriz: a oferta aparece sozinha. Abra a *Rastreabilidade do lote* e envie uma proposta (ex.: 300 kg a R$ 2,00).
4. Rafael: a proposta chega na hora. **Contrapropor** (R$ 2,30). Beatriz: **Aceitar**.
5. A venda fecha, o saldo é reservado e a entrega aparece em **Logística ao vivo** nas duas janelas.
   **Confirmar coleta** → **Simulação automática**: o veículo anda no mapa, com chegada prevista e temperatura.
6. Durante a viagem, **Simular falha de refrigeração** mostra o alerta; a chegada prevista depois do limite de uso também alerta.

## Endpoints

### Gerador (já existiam)
| Método | Rota | Função |
|---|---|---|
| GET | /api/dashboard | Visão geral |
| GET | /api/lotes?situacao=&busca= | Estoque |
| GET | /api/lotes/{codigo} | Detalhe do lote |
| POST | /api/lotes | Registrar excedente (quarentena) |
| POST | /api/lotes/{codigo}/liberacao | Liberação pela qualidade |
| GET | /api/lotes/{codigo}/simulacao-destino | Resultado previsto |
| POST | /api/lotes/{codigo}/destino | Destinar ao upcycling |
| GET | /api/processos, /api/ordens | Processos e ordens |

### Marketplace (novo)
| Método | Rota | Quem | Função |
|---|---|---|---|
| POST | /api/lotes/{codigo}/oferta | Rafael | Oferta o lote liberado (preço/kg, quantidade, mínimo) |
| DELETE | /api/lotes/{codigo}/oferta | Rafael | Retira a oferta (cancela negociações abertas) |
| GET | /api/compradores | | Compradores da demonstração |
| GET | /api/mercado/ofertas?busca= | Beatriz | Vitrine, ordenada por validade (FEFO) |
| GET | /api/mercado/ofertas/{lote} | Beatriz | Oferta com rastreabilidade e avaliação de qualidade |
| POST | /api/mercado/ofertas/{lote}/negociacoes | Beatriz | Proposta de preço e quantidade |
| GET | /api/negociacoes?compradorCodigo=&loteCodigo=&status= | ambos | Negociações |
| GET | /api/negociacoes/{codigo} | ambos | Histórico e valores |
| POST | /api/negociacoes/{codigo}/contraproposta | ambos | Contraproposta (`autor`: COMPRADOR ou VENDEDOR) |
| POST | /api/negociacoes/{codigo}/aceite | ambos | Aceita a última proposta do outro lado |
| POST | /api/negociacoes/{codigo}/recusa | ambos | Recusa |
| POST | /api/negociacoes/{codigo}/cancelamento | Beatriz | Desiste |

### Logística em tempo real (novo)
| Método | Rota | Função |
|---|---|---|
| GET | /api/entregas?compradorCodigo= | Entregas |
| GET | /api/entregas/{codigo} | Posição, temperatura, ETA, alertas e eventos |
| POST | /api/entregas/{codigo}/coleta | Motorista confirma a coleta |
| POST | /api/entregas/{codigo}/telemetria | `{latitude, longitude, temperaturaC}`: ponto de integração com app ou rastreador |
| POST | /api/entregas/{codigo}/confirmacao | Recebimento no destino |
| POST | /api/entregas/{codigo}/avanco | Demonstração: avança 15 min de viagem |
| POST | /api/entregas/{codigo}/simulacao | Demonstração: `{ "ativa": true }` liga o avanço automático |
| GET | /api/eventos | **SSE**: `ESTOQUE_ATUALIZADO`, `OFERTA_ATUALIZADA`, `NEGOCIACAO_ATUALIZADA`, `ENTREGA_ATUALIZADA` |

O evento traz `tipo`, `referencia` e `momento`; o cliente busca o dado novo na rota correspondente.
Para usar o Figma ligado à API, ou uma tela feita com vibe coding, basta chamar essas rotas (CORS liberado em `/api/**`).

## Regras de negócio novas
- Só lote **liberado pela qualidade**, dentro do limite de uso e com saldo pode ser ofertado.
- Proposta: quantidade entre o mínimo e o disponível da oferta; preço maior que zero.
- **Proposta no preço anunciado (ou acima) fecha a compra na hora.** O preço anunciado é o teto: a contraproposta do vendedor não o supera.
- As partes **alternam a vez**: só quem tem a vez aceita, recusa ou contrapropõe. O comprador pode cancelar a qualquer momento. Máximo de 8 rodadas.
- Aceite: revalida estoque e validade, **reserva o saldo**, abate a oferta e **cria a entrega**. Dois compradores não levam o mesmo saldo.
- Comissão da plataforma de **15%** (ponto médio do take-rate de 10% a 20% do dossiê) calculada sobre cada venda (`MercadoService.TAKE_RATE`).
- Entrega: `AGUARDANDO_COLETA → EM_TRANSITO → CHEGANDO (≥ 85% da rota) → ENTREGUE`.
  Alerta de temperatura acima do limite do lote (lido de "0 a 4 °C" ou "até 25 °C"; padrão 8 °C) e alerta quando a
  chegada prevista passa do limite de uso validado (velocidade média de 55 km/h).
- O lote registra a venda, a saída e a entrega na rastreabilidade.
- Novo processo aprovado `UP-08` (Farinha de bagaço de malte), para o piloto da cervejaria.

## Configuração (`application.properties`)
- `revalor.relogio.modo=corrente` (padrão): parte de 07/10/2026 10:00 e anda em tempo real. Use `fixo` para resultados idênticos a cada execução.
- `revalor.logistica.intervalo-ms=3000`: intervalo entre passos da simulação automática.

## Limites do MVP
- Sem autenticação: o papel (Rafael ou Beatriz) vem do que a tela envia (`autor`, `compradorCodigo`).
- Dados em memória: reiniciar a aplicação volta à carga demonstrativa.
- O mapa é esquemático (linha reta entre origem e destino); a posição real viria da telemetria do veículo.
