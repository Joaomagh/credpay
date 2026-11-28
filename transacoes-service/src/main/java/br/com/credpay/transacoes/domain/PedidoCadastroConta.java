package br.com.credpay.transacoes.domain;

import java.util.Locale;
import java.util.Arrays;
import java.util.regex.Pattern;

public record PedidoCadastroConta(String email, String senha) {
    private static final Pattern PARTE_LOCAL = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern ROTULO_DOMINIO = Pattern.compile("[a-z0-9-]+");

    public PedidoCadastroConta {
        if (email == null || email.length() > 512) {
            throw new IllegalArgumentException("Informe um e-mail válido.");
        }
        email = email.trim().toLowerCase(Locale.ROOT);
        if (!emailValido(email)) {
            throw new IllegalArgumentException("Informe um e-mail válido.");
        }
        if (senha == null || senha.length() > 256 || senha.codePointCount(0, senha.length()) < 8
                || senha.codePointCount(0, senha.length()) > 128
                || senha.codePoints().anyMatch(ponto -> Character.isISOControl(ponto)
                    || (ponto >= Character.MIN_SURROGATE && ponto <= Character.MAX_SURROGATE))) {
            throw new IllegalArgumentException("Use uma senha de 8 a 128 caracteres, sem caracteres de controle.");
        }
    }

    private static boolean emailValido(String valor) {
        int arroba = valor.indexOf('@');
        if (valor.length() > 254 || arroba < 1 || arroba > 64 || arroba != valor.lastIndexOf('@')) {
            return false;
        }
        if (Arrays.stream(valor.substring(0, arroba).split("\\.", -1))
                .anyMatch(parte -> !PARTE_LOCAL.matcher(parte).matches())) {
            return false;
        }
        var rotulos = valor.substring(arroba + 1).split("\\.", -1);
        if (rotulos.length < 2 || !Arrays.stream(rotulos).allMatch(PedidoCadastroConta::rotuloValido)) {
            return false;
        }
        var finalDominio = rotulos[rotulos.length - 1];
        return finalDominio.length() >= 2 && finalDominio.chars().allMatch(letra -> letra >= 'a' && letra <= 'z');
    }

    private static boolean rotuloValido(String valor) {
        return !valor.isEmpty() && valor.length() <= 63 && valor.charAt(0) != '-'
                && valor.charAt(valor.length() - 1) != '-' && ROTULO_DOMINIO.matcher(valor).matches();
    }

    @Override
    public String toString() { return "PedidoCadastroConta[redigido]"; }
}
