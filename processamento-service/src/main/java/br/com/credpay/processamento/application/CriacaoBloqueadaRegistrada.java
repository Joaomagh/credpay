package br.com.credpay.processamento.application;

import java.util.UUID;

public record CriacaoBloqueadaRegistrada(TransacaoCriadaRecebida entrada, UUID requestEventId)
        implements RecebimentoProcessamento {
}
