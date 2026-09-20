package br.com.credpay.processamento.infrastructure.configuration;

import br.com.credpay.processamento.application.LimitesProcessamento;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "credpay.processamento")
public record LimitesProcessamentoProperties(Map<String, BigDecimal> limites) implements LimitesProcessamento {

    public LimitesProcessamentoProperties {
        if (limites == null || limites.isEmpty()) {
            throw new IllegalArgumentException("ao menos um limite por moeda deve ser informado");
        }
        var normalizados = new HashMap<String, BigDecimal>();
        limites.forEach((moeda, limite) -> {
            String codigo;
            try {
                codigo = Currency.getInstance(moeda.toUpperCase(Locale.ROOT)).getCurrencyCode();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("moeda deve ser um codigo ISO 4217 valido", exception);
            }
            if (limite == null || limite.signum() <= 0) {
                throw new IllegalArgumentException("limite deve ser maior que zero");
            }
            if (normalizados.putIfAbsent(codigo, limite) != null) {
                throw new IllegalArgumentException("limite duplicado para moeda " + codigo);
            }
        });
        limites = Map.copyOf(normalizados);
    }

    @Override
    public BigDecimal limitePara(Currency moeda) {
        if (moeda == null) {
            throw new IllegalArgumentException("moeda deve ser informada");
        }
        var limite = limites.get(moeda.getCurrencyCode());
        if (limite == null) {
            throw new IllegalArgumentException("limite nao configurado para moeda " + moeda.getCurrencyCode());
        }
        return limite;
    }
}
