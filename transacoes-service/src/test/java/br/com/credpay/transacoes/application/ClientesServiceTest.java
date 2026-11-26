package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;

import br.com.credpay.transacoes.domain.Cliente;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientesServiceTest {

    private static final UUID CONTA = UUID.fromString("9e80abcf-a0fe-4636-94ed-04fec4879e0c");

    @Test
    void cadastrar_devePersistirClienteDaConta_semImporUnicidadePorNome() {
        var persistidos = new ArrayList<Cliente>();
        var service = new ClientesService(new RepositorioFixture(persistidos));

        var primeiro = service.cadastrar(CONTA, " Cliente Exemplo ");
        var segundo = service.cadastrar(CONTA, "Cliente Exemplo");

        assertThat(primeiro).isNotNull();
        assertThat(segundo).isNotNull();
        assertThat(primeiro.id()).isNotNull().isNotEqualTo(segundo.id());
        assertThat(primeiro.nome()).isEqualTo("Cliente Exemplo");
        assertThat(persistidos).containsExactly(
                new Cliente(primeiro.id(), CONTA, "Cliente Exemplo"),
                new Cliente(segundo.id(), CONTA, "Cliente Exemplo"));
    }

    @Test
    void cadastrar_deveRecusarNomeInvalido_semPersistir() {
        var persistidos = new ArrayList<Cliente>();
        var service = new ClientesService(new RepositorioFixture(persistidos));

        assertThatThrownBy(() -> service.cadastrar(CONTA, "\u00a0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nome deve conter entre 1 e 120 caracteres, sem controles");
        assertThat(persistidos).isEmpty();
    }

    @Test
    void cadastrar_deveRecusarContaAusente_semPersistir() {
        var persistidos = new ArrayList<Cliente>();
        var service = new ClientesService(new RepositorioFixture(persistidos));

        assertThatThrownBy(() -> service.cadastrar(null, "Cliente Exemplo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("conta deve ser informada");
        assertThat(persistidos).isEmpty();
    }

    @Test
    void cadastrar_devePropagarFalhaDePersistencia_semRetornarSucesso() {
        var falha = new IllegalStateException("persistência indisponível");
        var service = new ClientesService(new RepositorioFixture(new ArrayList<>()) {
            @Override
            public void inserir(Cliente cliente) { throw falha; }
        });

        assertThatThrownBy(() -> service.cadastrar(CONTA, "Cliente Exemplo")).isSameAs(falha);
    }

    @Test
    void buscar_deveRetornarClienteDaConta_semExporClienteAlheio() {
        var proprio = new Cliente(UUID.randomUUID(), CONTA, "Cliente Exemplo");
        var alheio = new Cliente(UUID.randomUUID(), UUID.randomUUID(), "Outro Cliente");
        var service = new ClientesService(new RepositorioFixture(List.of(proprio, alheio)));

        assertThat(service.buscar(CONTA, proprio.id())).isEqualTo(new Clientes.Resultado(proprio.id(), proprio.nome()));
        assertThatThrownBy(() -> service.buscar(CONTA, alheio.id()))
                .isInstanceOf(ClienteNaoEncontradoException.class).hasMessage("cliente não encontrado");
        assertThatThrownBy(() -> service.buscar(CONTA, UUID.randomUUID()))
                .isInstanceOf(ClienteNaoEncontradoException.class).hasMessage("cliente não encontrado");
    }

    @Test
    void listar_devePaginarSemPularClientes_eSemMisturarContas() {
        var primeiro = new Cliente(UUID.fromString("00000000-0000-4000-8000-000000000001"), CONTA, "Primeiro");
        var segundo = new Cliente(UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff"), CONTA, "Segundo");
        var terceiro = new Cliente(UUID.fromString("80000000-0000-4000-8000-000000000000"), CONTA, "Terceiro");
        var quarto = new Cliente(UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"), CONTA, "Quarto");
        var alheio = new Cliente(UUID.fromString("00000000-0000-4000-8000-000000000002"), UUID.randomUUID(), "Alheio");
        var service = new ClientesService(new RepositorioFixture(List.of(quarto, terceiro, alheio, segundo, primeiro)));

        var pagina = service.listar(CONTA, 2, null);

        assertThat(pagina).isNotNull();
        assertThat(pagina.itens()).extracting(Clientes.Resultado::id).containsExactly(primeiro.id(), segundo.id());
        assertThat(pagina.proximo()).isEqualTo(segundo.id());
        var proxima = service.listar(CONTA, 2, pagina.proximo());
        assertThat(proxima.itens()).extracting(Clientes.Resultado::id).containsExactly(terceiro.id(), quarto.id());
        assertThat(proxima.proximo()).isNull();
    }

    @Test
    void listar_deveRetornarPaginaVazia_quandoContaNaoTiverClientes() {
        var service = new ClientesService(new RepositorioFixture(List.of()));

        var pagina = service.listar(CONTA, 20, null);

        assertThat(pagina).isNotNull();
        assertThat(pagina.itens()).isEmpty();
        assertThat(pagina.proximo()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, Integer.MAX_VALUE})
    void listar_deveRecusarLimiteInvalido_naFronteiraDaAplicacao(int limite) {
        var service = new ClientesService(new RepositorioFixture(List.of()));

        assertThatThrownBy(() -> service.listar(CONTA, limite, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("limite deve estar entre 1 e 50");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consultar_deveExigirConta_naFronteiraDaAplicacao(boolean listar) {
        var service = new ClientesService(new RepositorioFixture(List.of()));

        assertThatThrownBy(() -> {
            if (listar) { service.listar(null, 20, null); }
            else { service.buscar(null, UUID.randomUUID()); }
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("conta deve ser informada");
    }

    private static class RepositorioFixture implements ClienteRepository {
        private final List<Cliente> dados;

        RepositorioFixture(List<Cliente> dados) { this.dados = dados; }

        @Override
        public void inserir(Cliente cliente) { dados.add(cliente); }

        @Override
        public Optional<Cliente> buscar(UUID contaId, UUID clienteId) {
            return dados.stream().filter(cliente -> cliente.contaId().equals(contaId)
                    && cliente.id().equals(clienteId)).findFirst();
        }

        @Override
        public List<Cliente> listar(UUID contaId, int limite, UUID apos) {
            return dados.stream().filter(cliente -> cliente.contaId().equals(contaId))
                    .filter(cliente -> apos == null || cliente.id().toString().compareTo(apos.toString()) > 0)
                    .sorted(Comparator.comparing(cliente -> cliente.id().toString())).limit(limite).toList();
        }
    }
}
