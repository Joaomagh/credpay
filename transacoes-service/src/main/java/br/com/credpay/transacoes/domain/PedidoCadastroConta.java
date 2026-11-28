package br.com.credpay.transacoes.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public record PedidoCadastroConta(String email, String senha) {
    private static final String LOCAL = "[a-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final String ROTULO = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern EMAIL = Pattern.compile(LOCAL + "(?:\\." + LOCAL + ")*@"
            + ROTULO + "(?:\\." + ROTULO + ")*\\.[a-z]{2,63}");

    public PedidoCadastroConta {
        if (email == null || email.length() > 512) {
            throw new IllegalArgumentException("Informe um e-mail válido.");
        }
        email = email.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || email.indexOf('@') > 64 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Informe um e-mail válido.");
        }
        if (senha == null || senha.length() > 256 || senha.codePointCount(0, senha.length()) < 8
                || senha.codePointCount(0, senha.length()) > 128
                || senha.codePoints().anyMatch(ponto -> Character.isISOControl(ponto)
                    || (ponto >= Character.MIN_SURROGATE && ponto <= Character.MAX_SURROGATE))) {
            throw new IllegalArgumentException("Use uma senha de 8 a 128 caracteres, sem caracteres de controle.");
        }
    }

    @Override
    public String toString() { return "PedidoCadastroConta[redigido]"; }
}
