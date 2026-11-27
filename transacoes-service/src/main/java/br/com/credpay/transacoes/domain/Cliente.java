package br.com.credpay.transacoes.domain;

import java.util.UUID;

public record Cliente(UUID id, UUID contaId, String nome) {

    public Cliente {
        if (id == null) {
            throw new IllegalArgumentException("id deve ser informado");
        }
        if (contaId == null) {
            throw new IllegalArgumentException("conta deve ser informada");
        }
        if (nome == null || nome.codePoints().anyMatch(Cliente::caractereInvalido)) {
            throw nomeInvalido();
        }
        int inicio = 0;
        int fim = nome.length();
        while (inicio < fim && espaco(nome.codePointAt(inicio))) {
            inicio += Character.charCount(nome.codePointAt(inicio));
        }
        while (fim > inicio && espaco(nome.codePointBefore(fim))) {
            fim -= Character.charCount(nome.codePointBefore(fim));
        }
        nome = nome.substring(inicio, fim);
        int tamanho = nome.codePointCount(0, nome.length());
        if (tamanho < 1 || tamanho > 120) {
            throw nomeInvalido();
        }
    }

    private static boolean espaco(int caractere) {
        return Character.isWhitespace(caractere) || Character.isSpaceChar(caractere);
    }

    private static boolean caractereInvalido(int caractere) {
        return Character.isISOControl(caractere)
                || (caractere >= Character.MIN_SURROGATE && caractere <= Character.MAX_SURROGATE);
    }

    private static IllegalArgumentException nomeInvalido() {
        return new IllegalArgumentException("nome deve conter entre 1 e 120 caracteres, sem controles");
    }
}
