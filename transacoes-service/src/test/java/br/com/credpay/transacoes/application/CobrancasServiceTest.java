package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Cliente;
import br.com.credpay.transacoes.domain.Cobranca;
import br.com.credpay.transacoes.domain.PedidoCobranca;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CobrancasServiceTest {
    private static final UUID CONTA = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OUTRA = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CLIENTE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CLIENTE_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CHAVE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-11-27T18:00:00Z"), ZoneOffset.UTC);
    private final Map<UUID, Cliente> cadastro = new HashMap<>(Map.of(
            CLIENTE, new Cliente(CLIENTE, CONTA, "Cliente A"),
            CLIENTE_B, new Cliente(CLIENTE_B, OUTRA, "Cliente B")));
    private final Memoria repository = new Memoria();
    private final ClienteRepository clientes = new ClienteRepository() {
        @Override
        public void inserir(Cliente cliente) { cadastro.put(cliente.id(), cliente); }
        @Override
        public Optional<Cliente> buscar(UUID contaId, UUID clienteId) {
            return Optional.ofNullable(cadastro.get(clienteId)).filter(cliente -> cliente.contaId().equals(contaId));
        }
        @Override
        public List<Cliente> listar(UUID contaId, int limite, UUID apos) { return List.of(); }
    };
    private final CobrancasService service = new CobrancasService(repository, clientes, RELOGIO);

    private PedidoCobranca pedido(UUID cliente) {
        return new PedidoCobranca(cliente, "Serviço mensal", new BigDecimal("250"), "BRL", LocalDate.of(2026, 12, 10));
    }

    @Test
    void criar_deveVincularContaClienteEInstanteDoRelogio() {
        var cobranca = service.criar(CONTA, CHAVE, pedido(CLIENTE));
        assertThat(cobranca).isNotNull();
        assertThat(cobranca.id()).isNotNull();
        assertThat(cobranca.contaId()).isEqualTo(CONTA);
        assertThat(cobranca.pedido()).isEqualTo(pedido(CLIENTE));
        assertThat(cobranca.criadaEm()).isEqualTo(RELOGIO.instant());
        assertThat(repository.dados).containsValue(cobranca).hasSize(1);
    }

    @Test
    void criar_deveFixarInstanteEmMicrossegundosParaPersistenciaEReplay() {
        var instante = Instant.parse("2026-11-27T18:00:00.123456789Z");
        var preciso = new CobrancasService(repository, clientes, Clock.fixed(instante, ZoneOffset.UTC));
        assertThat(preciso.criar(CONTA, CHAVE, pedido(CLIENTE)).criadaEm())
                .isEqualTo(Instant.parse("2026-11-27T18:00:00.123456Z"));
    }

    @Test
    void repetir_devePreservarIdentidadeEInstanteParaPedidoEquivalente() {
        var original = service.criar(CONTA, CHAVE, pedido(CLIENTE));
        var depois = new CobrancasService(repository, clientes, Clock.offset(RELOGIO, Duration.ofHours(1)));
        var equivalente = new PedidoCobranca(CLIENTE, "  Serviço mensal  ", new BigDecimal("250.000"),
                "BRL", LocalDate.of(2026, 12, 10));
        assertThat(depois.criar(CONTA, CHAVE, equivalente)).isEqualTo(original);
        assertThat(repository.dados).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cliente", "descricao", "valor", "vencimento"})
    void repetir_deveRecusarPayloadDiferente_semSobrescreverOriginal(String campo) {
        var original = service.criar(CONTA, CHAVE, pedido(CLIENTE));
        cadastro.put(CLIENTE_B, new Cliente(CLIENTE_B, CONTA, "Outro cliente da conta"));
        var diferente = new PedidoCobranca(campo.equals("cliente") ? CLIENTE_B : CLIENTE,
                campo.equals("descricao") ? "Outro serviço" : "Serviço mensal",
                new BigDecimal(campo.equals("valor") ? "251" : "250"), "BRL",
                LocalDate.of(2026, 12, campo.equals("vencimento") ? 11 : 10));
        assertThatThrownBy(() -> service.criar(CONTA, CHAVE, diferente))
                .isInstanceOf(ConflitoIdempotenciaException.class);
        assertThat(repository.dados).containsValue(original).hasSize(1);
    }

    @Test
    void mesmaChave_deveSerIndependenteEmContasDistintas() {
        var a = service.criar(CONTA, CHAVE, pedido(CLIENTE));
        var b = service.criar(OUTRA, CHAVE, pedido(CLIENTE_B));
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(repository.dados).hasSize(2);
    }

    @Test
    void clienteAusenteOuAlheio_deveSerRecusadoSemGravar() {
        assertThatThrownBy(() -> service.criar(CONTA, CHAVE, pedido(CLIENTE_B)))
                .isInstanceOf(ClienteNaoEncontradoException.class);
        assertThatThrownBy(() -> service.criar(CONTA, CHAVE, pedido(UUID.randomUUID())))
                .isInstanceOf(ClienteNaoEncontradoException.class);
        assertThat(repository.dados).isEmpty();
    }

    @Test
    void criar_deveExigirContaChaveEPedido() {
        assertThatThrownBy(() -> service.criar(null, CHAVE, pedido(CLIENTE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.criar(CONTA, null, pedido(CLIENTE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.criar(CONTA, CHAVE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.dados).isEmpty();
    }

    private record Chave(UUID conta, UUID chave) { }

    private static class Memoria implements CobrancaRepository {
        private final Map<Chave, Cobranca> dados = new HashMap<>();
        @Override
        public void bloquearChave(UUID contaId, UUID chave) { }
        @Override
        public Optional<Cobranca> buscarPorChave(UUID contaId, UUID chave) {
            return Optional.ofNullable(dados.get(new Chave(contaId, chave)));
        }
        @Override
        public void inserir(UUID chave, Cobranca cobranca) {
            dados.put(new Chave(cobranca.contaId(), chave), cobranca);
        }
    }
}
