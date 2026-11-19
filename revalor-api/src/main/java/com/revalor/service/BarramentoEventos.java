package com.revalor.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Barramento de eventos em memória. Cada mudança de estoque, negociação ou entrega é publicada aqui
 * e entregue em tempo real aos clientes conectados (veja GET /api/eventos, Server-Sent Events).
 */
@Component
public class BarramentoEventos {

    /** tipo: ESTOQUE_ATUALIZADO, OFERTA_ATUALIZADA, NEGOCIACAO_ATUALIZADA ou ENTREGA_ATUALIZADA. */
    public record Evento(String tipo, String referencia, LocalDateTime momento) {}

    private final Set<Consumer<Evento>> ouvintes = new CopyOnWriteArraySet<>();

    /** Registra um ouvinte e devolve a ação que o remove. */
    public Runnable inscrever(Consumer<Evento> ouvinte) {
        ouvintes.add(ouvinte);
        return () -> ouvintes.remove(ouvinte);
    }

    public void publicar(String tipo, String referencia, LocalDateTime momento) {
        Evento evento = new Evento(tipo, referencia, momento);
        for (Consumer<Evento> o : ouvintes) {
            try {
                o.accept(evento);
            } catch (RuntimeException ignorado) {
                // um cliente com problema não pode derrubar a operação de negócio
            }
        }
    }

    public int ouvintes() { return ouvintes.size(); }
}
