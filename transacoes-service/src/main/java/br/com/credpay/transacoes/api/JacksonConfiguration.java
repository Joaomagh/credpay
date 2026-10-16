package br.com.credpay.transacoes.api;

import java.math.BigDecimal;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer rejeitarNumeroOuBooleanoComoTexto() {
        return builder -> builder.withCoercionConfig(String.class, coercion -> coercion
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    JsonMapperBuilderCustomizer rejeitarTextoComoBigDecimal() {
        return builder -> builder.withCoercionConfig(BigDecimal.class, coercion -> coercion
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
    }
}
