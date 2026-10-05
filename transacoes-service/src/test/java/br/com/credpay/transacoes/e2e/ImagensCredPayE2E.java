package br.com.credpay.transacoes.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class ImagensCredPayE2E {

    private static final String POSTGRES_IMAGE = "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0";
    private static final String RABBIT_IMAGE = "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void imagens_deveIniciarNonRootComHealthEExchanges_quandoConsumoDesligado() throws Exception {
        var tag = System.getenv("CREDPAY_IMAGE_TAG");
        assertThat(tag).as("tag das duas imagens locais: SHA completo do checkout").matches("[0-9a-f]{40}");

        try (var rede = Network.newNetwork();
             var bancoTransacoes = banco(rede, "db-transacoes", "credpay_transacoes_imagens");
             var bancoProcessamento = banco(rede, "db-processamento", "credpay_processamento_imagens");
             var broker = new RabbitMQContainer(DockerImageName.parse(RABBIT_IMAGE).asCompatibleSubstituteFor("rabbitmq"))
                     .withAdminUser("credpay_test").withAdminPassword("test")
                     .withNetwork(rede).withNetworkAliases("rabbit");
             var transacoes = aplicativo("transacoes", tag, rede, "db-transacoes", bancoTransacoes, broker);
             var processamento = aplicativo("processamento", tag, rede, "db-processamento", bancoProcessamento, broker)) {
            bancoTransacoes.start();
            bancoProcessamento.start();
            broker.start();
            transacoes.start();
            processamento.start();

            conferirUsuarioEMounts(transacoes, "transacoes");
            conferirUsuarioEMounts(processamento, "processamento");
            var exchanges = consultar(broker, "list_exchanges", "name", "type", "durable");
            conferirExchange(exchanges, "credpay.transacoes.v1");
            conferirExchange(exchanges, "credpay.processamento.v1");
            assertThat(consultar(broker, "list_consumers")).as("consumo desligado nos dois serviços").isEmpty();
            System.out.println("ImagensCredPayE2E: duas exchanges direct/duráveis, consumidores=0");
        }
    }

    private PostgreSQLContainer<?> banco(Network rede, String alias, String nome) {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(nome).withUsername("test").withPassword("test")
                .withNetwork(rede).withNetworkAliases(alias);
    }

    private GenericContainer<?> aplicativo(String servico, String tag, Network rede, String aliasBanco,
                                           PostgreSQLContainer<?> banco, RabbitMQContainer broker) {
        return new GenericContainer<>(DockerImageName.parse("credpay-" + servico + ":" + tag))
                .withImagePullPolicy(imagem -> false)
                .withNetwork(rede).withExposedPorts(8080)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://" + aliasBanco + ":5432/" + banco.getDatabaseName())
                .withEnv("SPRING_DATASOURCE_USERNAME", banco.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", banco.getPassword())
                .withEnv("SPRING_RABBITMQ_HOST", "rabbit")
                .withEnv("SPRING_RABBITMQ_PORT", "5672")
                .withEnv("SPRING_RABBITMQ_USERNAME", broker.getAdminUsername())
                .withEnv("SPRING_RABBITMQ_PASSWORD", broker.getAdminPassword())
                .withEnv("SPRING_RABBITMQ_VIRTUAL_HOST", "/")
                .withCommand("--server.port=8080", "--management.health.rabbit.enabled=true",
                        "--credpay." + servico + ".consumer.topology.enabled=false",
                        "--credpay." + servico + ".consumer.listener.enabled=false",
                        "--credpay.outbox.publisher.enabled=false", "--credpay.processamento.limites.BRL=100.00")
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200)
                        .forResponsePredicate(this::healthUp).withStartupTimeout(Duration.ofSeconds(90)));
    }

    private boolean healthUp(String corpo) {
        try {
            return "UP".equals(JSON.readTree(corpo).path("status").asText());
        } catch (Exception respostaInvalida) {
            return false;
        }
    }

    private void conferirUsuarioEMounts(GenericContainer<?> aplicativo, String servico) throws Exception {
        var usuario = aplicativo.execInContainer("id", "-u");
        assertThat(usuario.getExitCode()).as("id -u: " + servico).isZero();
        assertThat(usuario.getStdout().strip()).as("UID efetivo: " + servico).isEqualTo("10001");
        var info = aplicativo.getContainerInfo();
        assertThat(info.getConfig().getUser()).as("usuário configurado: " + servico).isEqualTo("10001:10001");
        assertThat(info.getMounts()).as("nenhum mount no aplicativo: " + servico).isEmpty();
        assertThat(info.getHostConfig().getPrivileged()).as("sem modo privilegiado: " + servico).isFalse();
        System.out.println("ImagensCredPayE2E: " + servico + " uid=10001, mounts=0, privileged=false, health=UP");
    }

    private JsonNode consultar(RabbitMQContainer broker, String comando, String... campos) throws Exception {
        var argumentos = new ArrayList<>(List.of("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json", comando));
        argumentos.addAll(List.of(campos));
        var resultado = broker.execInContainer(argumentos.toArray(String[]::new));
        assertThat(resultado.getExitCode()).as("consulta do broker: " + comando).isZero();
        var linhas = JSON.readTree(resultado.getStdout());
        assertThat(linhas).as("JSON do broker: " + comando).isNotNull();
        assertThat(linhas.isArray()).as("array do broker: " + comando).isTrue();
        return linhas;
    }

    private void conferirExchange(JsonNode exchanges, String nome) {
        var encontradas = StreamSupport.stream(exchanges.spliterator(), false)
                .filter(exchange -> nome.equals(exchange.path("name").asText())).toList();
        assertThat(encontradas).as("exchange produtora: " + nome).hasSize(1);
        assertThat(encontradas.getFirst().path("type").asText()).isEqualTo("direct");
        assertThat(encontradas.getFirst().path("durable").asBoolean()).isTrue();
    }
}
