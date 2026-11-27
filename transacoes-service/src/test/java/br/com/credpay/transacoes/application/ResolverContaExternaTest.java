package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.IdentidadeExterna;
import org.junit.jupiter.api.Test;

class ResolverContaExternaTest {
    private static final String GOOGLE = "https://accounts.google.com";
    private static final UUID CONTA_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONTA_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final Map<IdentidadeExterna, UUID> vinculos = Map.of(
            new IdentidadeExterna(GOOGLE, "sujeito-a"), CONTA_A,
            new IdentidadeExterna(GOOGLE, "sujeito-b"), CONTA_B);
    private final ResolverContaExterna resolver = new ResolverContaExterna(
            identidade -> Optional.ofNullable(vinculos.get(identidade)));

    @Test
    void devePreservarAContaLocalDoVinculoExistente() {
        assertThat(resolver.resolver(new IdentidadeExterna(GOOGLE, "sujeito-a"))).isEqualTo(CONTA_A);
        assertThat(resolver.resolver(new IdentidadeExterna(GOOGLE, "sujeito-b"))).isEqualTo(CONTA_B);
    }

    @Test
    void deveRecusarSujeitoDesconhecido_semUsarOutraConta() {
        assertThatThrownBy(() -> resolver.resolver(new IdentidadeExterna(GOOGLE, "desconhecido")))
                .isInstanceOf(SecurityException.class).hasMessage("Identidade externa sem vínculo");
    }

    @Test
    void deveConsiderarOEmissorNaIdentidade() {
        assertThatThrownBy(() -> resolver.resolver(
                new IdentidadeExterna("https://outro.example", "sujeito-a")))
                .isInstanceOf(SecurityException.class).hasMessage("Identidade externa sem vínculo");
    }

    @Test
    void devePreservarMaiusculasDoSujeito_semNormalizarIdentidade() {
        assertThatThrownBy(() -> resolver.resolver(new IdentidadeExterna(GOOGLE, "SUJEITO-A")))
                .isInstanceOf(SecurityException.class).hasMessage("Identidade externa sem vínculo");
    }
}
