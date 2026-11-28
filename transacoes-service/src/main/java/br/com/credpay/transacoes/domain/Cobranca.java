package br.com.credpay.transacoes.domain;

import java.time.Instant;
import java.util.UUID;

public record Cobranca(UUID id, UUID contaId, PedidoCobranca pedido, Instant criadaEm) {
}
