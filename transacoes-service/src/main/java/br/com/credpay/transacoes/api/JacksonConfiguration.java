package br.com.credpay.transacoes.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class JacksonConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer rejeitarNumeroOuBooleanoComoTexto() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(String.class)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer rejeitarTextoComoBigDecimal() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(BigDecimal.class)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
    }
}
