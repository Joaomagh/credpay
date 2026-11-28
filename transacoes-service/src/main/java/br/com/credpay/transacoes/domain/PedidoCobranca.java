package br.com.credpay.transacoes.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PedidoCobranca(UUID clienteId, String descricao, BigDecimal valor, String moeda, LocalDate vencimento) {
    public PedidoCobranca {
        if (clienteId == null) { throw new IllegalArgumentException("cliente deve ser informado"); }
        descricao = normalizarDescricao(descricao);
        if (!"BRL".equals(moeda)) { throw new IllegalArgumentException("moeda deve ser BRL"); }
        if (valor == null || valor.signum() <= 0 || (long) valor.precision() - valor.scale() > 17) {
            throw new IllegalArgumentException("valor BRL inválido");
        }
        valor = valor.stripTrailingZeros();
        if (valor.scale() > 2) { throw new IllegalArgumentException("valor BRL inválido"); }
        valor = valor.setScale(2);
        if (vencimento == null || vencimento.getYear() < 1 || vencimento.getYear() > 9999) {
            throw new IllegalArgumentException("vencimento deve ser uma data de 0001 a 9999");
        }
    }

    private static String normalizarDescricao(String descricao) {
        if (descricao == null || descricao.codePoints().anyMatch(caractere -> Character.isISOControl(caractere)
                || (caractere >= Character.MIN_SURROGATE && caractere <= Character.MAX_SURROGATE))) {
            throw descricaoInvalida();
        }
        int inicio = 0;
        int fim = descricao.length();
        while (inicio < fim && espaco(descricao.codePointAt(inicio))) {
            inicio += Character.charCount(descricao.codePointAt(inicio));
        }
        while (fim > inicio && espaco(descricao.codePointBefore(fim))) {
            fim -= Character.charCount(descricao.codePointBefore(fim));
        }
        var resultado = descricao.substring(inicio, fim);
        int tamanho = resultado.codePointCount(0, resultado.length());
        if (tamanho < 1 || tamanho > 240) { throw descricaoInvalida(); }
        return resultado;
    }

    private static boolean espaco(int caractere) {
        return Character.isWhitespace(caractere) || Character.isSpaceChar(caractere);
    }

    private static IllegalArgumentException descricaoInvalida() {
        return new IllegalArgumentException("descrição deve conter de 1 a 240 caracteres, sem controles");
    }
}
