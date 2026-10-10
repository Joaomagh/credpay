package br.com.credpay.transacoes.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FluxoCredPayE2E {

    private static final String POSTGRES_IMAGE = "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0";
    private static final Path RAIZ = Path.of("..").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final List<String> FILAS = List.of("credpay.processamento.transacao-criada.v1",
            "credpay.processamento.transacao-criada.dlq.v1", "credpay.transacoes.transacao-processada.v1",
            "credpay.transacoes.transacao-processada.dlq.v1");

    @Container static final PostgreSQLContainer<?> TRANSACOES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("credpay_transacoes_e2e").withUsername("test").withPassword("test");
    @Container static final PostgreSQLContainer<?> PROCESSAMENTO = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("credpay_processamento_e2e").withUsername("test").withPassword("test");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse(
            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
            .asCompatibleSubstituteFor("rabbitmq"))
            .withCopyFileToContainer(MountableFile.forHostPath(RAIZ.resolve("infra/rabbitmq/processamento-policies.json")),
                    "/tmp/processamento-policies.json")
            .withCopyFileToContainer(MountableFile.forHostPath(RAIZ.resolve("infra/rabbitmq/transacoes-policies.json")),
                    "/tmp/transacoes-policies.json");

    private final List<Process> aplicativos = new ArrayList<>();
    private final Path logs = RAIZ.resolve(".local/e2e/" + UUID.randomUUID());
    private JdbcTemplate transacoes;
    private JdbcTemplate processamento;
    private int portaTransacoes;
    private int portaProcessamento;
    private int fase;
    private CachingConnectionFactory republicacao;
    private RabbitTemplate rabbit;
    private int duplicatasEntrada;
    private int duplicatasSaida;
    private Process processoProcessamento;

    @BeforeAll
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void prepararAplicativosReais() throws Exception {
        try {
            transacoes = jdbc(TRANSACOES);
            processamento = jdbc(PROCESSAMENTO);
            iniciar(false, false);
            var exchanges = consultar("list_exchanges", "name", "type", "durable");
            conferirExchange(exchanges, "credpay.transacoes.v1");
            conferirExchange(exchanges, "credpay.processamento.v1");
            encerrarAplicativos();

            iniciar(true, false);
            importar("processamento");
            importar("transacoes");
            conferirPreparacao();
            assertThat(consultar("list_consumers")).isEmpty();
            encerrarAplicativos();

            iniciar(true, true);
            conferirConsumidores();
            assertThat(transacoes.queryForObject("SELECT to_regclass('public.processamentos')", String.class)).isNull();
            assertThat(processamento.queryForObject("SELECT to_regclass('public.transacoes')", String.class)).isNull();
            republicacao = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
            republicacao.setUsername(RABBIT.getAdminUsername());
            republicacao.setPassword(RABBIT.getAdminPassword());
            republicacao.setVirtualHost("/");
            republicacao.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            republicacao.setPublisherReturns(true);
            rabbit = new RabbitTemplate(republicacao);
            rabbit.setMandatory(true);
        } catch (Exception | AssertionError falha) {
            encerrarAplicativos();
            throw falha;
        }
    }

    @AfterAll
    void encerrar() throws InterruptedException {
        try { encerrarAplicativos(); }
        finally { if (republicacao != null) republicacao.destroy(); }
    }

    @ParameterizedTest
    @Order(1)
    @CsvSource({"50.000,APROVADA", "150.000,REJEITADA"})
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void post_deveConcluirEConservarResultadoNoReplayDosEventosEDoPost(String valor, String status) throws Exception {
        var chave = UUID.randomUUID();
        var resposta = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", chave.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":" + valor + ",\"moeda\":\"BRL\"}")));
        assertThat(resposta.statusCode()).isEqualTo(201);
        var original = JSON.readTree(resposta.body());
        assertThat(original.path("status").asText()).isEqualTo("PENDENTE");
        var id = UUID.fromString(original.path("id").asText());
        var location = resposta.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/transacoes/" + id);

        var finalizada = aguardarFinal(location, status);
        assertThat(finalizada.path("id").asText()).isEqualTo(id.toString());
        assertThat(finalizada.path("valor").decimalValue()).isEqualByComparingTo(new BigDecimal(valor));
        assertThat(finalizada.path("moeda").asText()).isEqualTo("BRL");
        aguardarPublicacoes(id);

        var entrada = transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var registro = processamento.queryForMap("SELECT * FROM processamentos WHERE transaction_id = ?", id);
        var saida = processamento.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var historico = transacoes.queryForMap("SELECT * FROM historico_transacoes WHERE transaction_id = ?", id);
        assertThat(registro.get("event_id")).isEqualTo(entrada.get("event_id"));
        assertThat(registro.get("output_event_id")).isEqualTo(saida.get("event_id"));
        assertThat(registro.get("correlation_id")).isEqualTo(id);
        assertThat(registro.get("resultado")).isEqualTo(status);
        assertThat((BigDecimal) registro.get("valor")).isEqualByComparingTo(new BigDecimal(valor));
        assertThat(registro.get("moeda")).isEqualTo("BRL");
        assertThat((BigDecimal) registro.get("limite_aplicado")).isEqualByComparingTo("100.00");
        assertThat(historico.get("event_id")).isEqualTo(saida.get("event_id"));
        assertThat(historico.get("causation_id")).isEqualTo(entrada.get("event_id"));
        assertThat(historico.get("correlation_id")).isEqualTo(id);
        assertThat(historico.get("estado_anterior")).isEqualTo("PENDENTE");
        assertThat(historico.get("estado_final")).isEqualTo(status);
        assertThat(historico.get("origem")).isEqualTo("processamento-service/TransacaoProcessada.v1");
        var payload = JSON.readTree(saida.get("payload").toString());
        assertThat(payload.path("causationId").asText()).isEqualTo(entrada.get("event_id").toString());
        assertThat(payload.path("data").path("status").asText()).isEqualTo(status);
        var ocorrido = Instant.parse(payload.path("occurredAt").asText());
        assertThat(historico.get("occurred_at_epoch_second")).isEqualTo(ocorrido.getEpochSecond());
        assertThat(historico.get("occurred_at_nano")).isEqualTo(ocorrido.getNano());
        assertThat(transacoes.queryForObject("SELECT scale(valor) FROM transacoes WHERE id = ?", Integer.class, id)).isEqualTo(3);
        conferirUnicidade(transacoes, "transacoes", "id", id);
        conferirUnicidade(transacoes, "historico_transacoes", "transaction_id", id);
        conferirUnicidade(transacoes, "outbox_eventos", "aggregate_id", id);
        conferirUnicidade(processamento, "processamentos", "transaction_id", id);
        conferirUnicidade(processamento, "outbox_eventos", "aggregate_id", id);
        aguardarFilasVazias();

        var antes = bancoInteiro();
        var originais = transacoes.queryForObject("SELECT COUNT(*) FROM transacoes", Integer.class);
        var ackEntrada = aguardarAck(FILAS.get(0), originais + duplicatasEntrada);
        var ackSaida = aguardarAck(FILAS.get(2), originais + duplicatasSaida);
        republicar(entrada, "credpay.transacoes.v1", "transacao.criada.v1");
        aguardarAck(FILAS.get(0), ackEntrada + 1);
        duplicatasEntrada++;
        republicar(saida, "credpay.processamento.v1", "transacao.processada.v1");
        aguardarAck(FILAS.get(2), ackSaida + 1);
        duplicatasSaida++;
        aguardarFilasVazias();
        assertThat(bancoInteiro()).withFailMessage("replay de evento alterou registros completos dos dois bancos").isEqualTo(antes);

        var replay = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", chave.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":" + new BigDecimal(valor).stripTrailingZeros().toPlainString()
                        + ",\"moeda\":\"BRL\"}")));
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Location")).contains(location);
        assertThat(replay.body()).withFailMessage("POST equivalente não preservou resposta original PENDENTE").isEqualTo(resposta.body());
        assertThat(aguardarFinal(location, status)).withFailMessage("replay alterou GET final").isEqualTo(finalizada);
        assertThat(bancoInteiro()).withFailMessage("replay de POST alterou registros completos dos dois bancos").isEqualTo(antes);
        aguardarFilasVazias();
    }

    @ParameterizedTest
    @Order(2)
    @CsvSource({"transacoes", "processamento"})
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void rotaAusente_deveObservarRetornoERecuperarMesmoEvento(String publicador) throws Exception {
        int porta = "transacoes".equals(publicador) ? portaTransacoes : portaProcessamento;
        var resposta = enviar(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/actuator/metrics")).GET());
        assertThat(resposta.statusCode()).withFailMessage("diagnostics HTTP esperado200, observado%d", resposta.statusCode())
                .isEqualTo(200);
        aguardarFilasVazias();
        aguardarOutboxesAnterioresPublicadas();
        boolean entradaSemRota = "transacoes".equals(publicador);
        var banco = entradaSemRota ? transacoes : processamento;
        var exchange = entradaSemRota ? "credpay.transacoes.v1" : "credpay.processamento.v1";
        var rota = entradaSemRota ? "transacao.criada.v1" : "transacao.processada.v1";
        var fila = FILAS.get(entradaSemRota ? 0 : 2);
        var binding = new Binding(fila, Binding.DestinationType.QUEUE, exchange, rota, null);
        var admin = new RabbitAdmin(republicacao);
        double baseline = lerReturned(publicador);
        UUID id;
        String location;
        Map<String, Object> pendente;
        Map<String, Object> transacaoAntes;
        Map<String, Object> decisaoAntes = null;
        try {
            admin.removeBinding(binding);
            var bindings = consultar("list_bindings", "source_name", "destination_name", "destination_kind", "routing_key");
            assertThat(StreamSupport.stream(bindings.spliterator(), false).filter(linha ->
                    exchange.equals(linha.path("source_name").asText()) && fila.equals(linha.path("destination_name").asText())
                    && "queue".equals(linha.path("destination_kind").asText()) && rota.equals(linha.path("routing_key").asText()))
                    .count()).withFailMessage("binding exato ainda presente antes do POST").isZero();
            var post = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                    .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50.000,\"moeda\":\"BRL\"}")));
            assertThat(post.statusCode()).isEqualTo(201);
            var original = jsonHttpSeguro(post.body());
            assertThat(original.path("status").asText()).isEqualTo("PENDENTE");
            id = UUID.fromString(original.path("id").asText());
            location = post.headers().firstValue("Location").orElseThrow();
            assertThat(location).withFailMessage("Location não corresponde à transação criada").isEqualTo("/transacoes/" + id);
            aguardarReturned(publicador, baseline + 1);
            pendente = banco.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
            transacaoAntes = new LinkedHashMap<>(transacoes.queryForMap("SELECT * FROM transacoes WHERE id = ?", id));
            assertThat(pendente.get("published_at")).withFailMessage("retorno marcou intenção como publicada").isNull();
            assertThat(aguardarFinal(location, "PENDENTE")).withFailMessage("retorno alterou GET PENDENTE").isEqualTo(original);
            assertThat(transacoes.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?", Integer.class, id))
                    .withFailMessage("retorno criou histórico final").isZero();
            if (entradaSemRota) {
                assertThat(processamento.queryForObject("SELECT COUNT(*) FROM processamentos WHERE transaction_id = ?", Integer.class, id))
                        .withFailMessage("entrada sem rota criou processamento").isZero();
                assertThat(processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?", Integer.class, id))
                        .withFailMessage("entrada sem rota criou saída").isZero();
            } else {
                aguardarCriacaoPublicada(id);
                decisaoAntes = processamento.queryForMap("SELECT * FROM processamentos WHERE transaction_id = ?", id);
                assertThat(decisaoAntes.get("resultado"))
                        .isEqualTo("APROVADA");
            }
        } finally {
            admin.declareBinding(binding);
        }
        var finalizada = aguardarFinal(location, "APROVADA");
        assertThat(finalizada.path("id").asText()).withFailMessage("recuperação alterou identidade da transação").isEqualTo(id.toString());
        assertThat(finalizada.path("valor").decimalValue()).withFailMessage("recuperação alterou valor da transação")
                .isEqualByComparingTo("50.000");
        assertThat(finalizada.path("moeda").asText()).isEqualTo("BRL");
        transacaoAntes.put("status", "APROVADA");
        assertThat(transacoes.queryForMap("SELECT * FROM transacoes WHERE id = ?", id))
                .withFailMessage("recuperação alterou dados além do status da transação").isEqualTo(transacaoAntes);
        aguardarPublicacoes(id);
        var publicada = banco.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        assertThat(publicada.get("published_at")).withFailMessage("recuperação não marcou publicação").isNotNull();
        var esperada = new LinkedHashMap<>(pendente);
        esperada.put("published_at", publicada.get("published_at"));
        assertThat(publicada).withFailMessage("recuperação alterou identidade/payload ou dados da intenção original").isEqualTo(esperada);
        var entrada = transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var saida = processamento.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var registro = processamento.queryForMap("SELECT * FROM processamentos WHERE transaction_id = ?", id);
        if (decisaoAntes != null) {
            assertThat(registro).withFailMessage("recuperação alterou snapshot de decisão já persistido").isEqualTo(decisaoAntes);
        }
        var historico = transacoes.queryForMap("SELECT * FROM historico_transacoes WHERE transaction_id = ?", id);
        assertThat(registro.get("event_id")).withFailMessage("recuperação mudou causa do processamento").isEqualTo(entrada.get("event_id"));
        assertThat(registro.get("output_event_id")).withFailMessage("recuperação mudou identidade da saída").isEqualTo(saida.get("event_id"));
        assertThat(historico.get("event_id")).withFailMessage("histórico não corresponde à saída original").isEqualTo(saida.get("event_id"));
        assertThat(historico.get("causation_id")).withFailMessage("histórico perdeu causalidade original").isEqualTo(entrada.get("event_id"));
        assertThat(historico.get("correlation_id")).withFailMessage("histórico perdeu correlação original").isEqualTo(id);
        conferirUnicidade(transacoes, "transacoes", "id", id);
        conferirUnicidade(transacoes, "outbox_eventos", "aggregate_id", id);
        conferirUnicidade(transacoes, "historico_transacoes", "transaction_id", id);
        conferirUnicidade(processamento, "processamentos", "transaction_id", id);
        conferirUnicidade(processamento, "outbox_eventos", "aggregate_id", id);
        aguardarFilasVazias();
        System.out.println("CredPay diagnostics: rota restaurada, evento original recuperado e registros únicos; publicador=" + publicador);
    }

    private double lerReturned(String publicador) throws Exception {
        int porta = "transacoes".equals(publicador) ? portaTransacoes : portaProcessamento;
        var endereco = URI.create("http://localhost:" + porta
                + "/actuator/metrics/credpay.messaging.publish.attempts?tag=outcome:returned");
        var resposta = enviar(HttpRequest.newBuilder(endereco).GET());
        if (resposta.statusCode() == 404) return 0;
        assertThat(resposta.statusCode()).withFailMessage("Consulta returned não respondeu HTTP 200").isEqualTo(200);
        var contador = jsonHttpSeguro(resposta.body());
        assertThat(contador).withFailMessage("Resposta returned vazia").isNotNull();
        assertThat(contador.path("name").asText()).withFailMessage("Nome do contador returned inválido")
                .isEqualTo("credpay.messaging.publish.attempts");
        var medidas = contador.path("measurements");
        assertThat(medidas.isArray() && medidas.size() == 1).withFailMessage("Medição returned inválida").isTrue();
        assertThat(medidas.get(0).path("statistic").asText()).withFailMessage("Estatística returned inválida").isEqualTo("COUNT");
        var valor = medidas.get(0).path("value");
        assertThat(valor.isNumber()).withFailMessage("COUNT returned não numérico").isTrue();
        double total = valor.asDouble();
        assertThat(Double.isFinite(total) && total >= 0).withFailMessage("COUNT returned inválido").isTrue();
        return total;
    }

    private JsonNode jsonHttpSeguro(String corpo) {
        try {
            return JSON.readTree(corpo);
        } catch (JsonProcessingException invalido) {
            throw new AssertionError("Resposta HTTP JSON inválida; corpo omitido.");
        }
    }

    private void aguardarReturned(String publicador, double minimo) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        double observado;
        do {
            observado = lerReturned(publicador);
            if (observado >= minimo) {
                System.out.println("CredPay diagnostics: returned observado no publicador=" + publicador);
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("returned mínimo não observado por HTTP; publicador=" + publicador + ", observado=" + observado);
    }

    private void aguardarOutboxesAnterioresPublicadas() throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (transacoes.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE published_at IS NULL", Integer.class) == 0
                    && processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE published_at IS NULL", Integer.class) == 0) return;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("outboxes anteriores não ficaram publicadas antes da falha controlada");
    }

    private void aguardarCriacaoPublicada(UUID id) throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (transacoes.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1) return;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("criação não ficou publicada enquanto saída permanecia sem rota");
    }

    @Test
    @Order(3)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void processadorParado_devePreservarPendenteERecuperarPeloEventoOriginal() throws Exception {
        aguardarFilasVazias();
        encerrarProcessos(List.of(processoProcessamento));
        aguardarSomenteConsumidorDeResultado();
        var resposta = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50.000,\"moeda\":\"BRL\"}")));
        assertThat(resposta.statusCode()).isEqualTo(201);
        var original = JSON.readTree(resposta.body());
        assertThat(original.path("status").asText()).isEqualTo("PENDENTE");
        var id = UUID.fromString(original.path("id").asText());
        var location = resposta.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/transacoes/" + id);
        aguardarEntradaPublicadaEPronta(id);
        assertThat(aguardarFinal(location, "PENDENTE")).withFailMessage("GET durante indisponibilidade não preservou resposta PENDENTE").isEqualTo(original);
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM processamentos WHERE transaction_id = ?", Integer.class, id)).isZero();
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?", Integer.class, id)).isZero();
        assertThat(transacoes.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?", Integer.class, id)).isZero();
        var entrada = transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var transacao = new LinkedHashMap<>(transacoes.queryForMap("SELECT * FROM transacoes WHERE id = ?", id));

        fase++;
        var porta = portaLivre();
        var reiniciado = iniciar("processamento", PROCESSAMENTO, porta, true, true);
        aguardarHealth(reiniciado, porta, "processamento reiniciado");
        conferirConsumidores();
        var finalizada = aguardarFinal(location, "APROVADA");
        assertThat(finalizada.path("id").asText()).isEqualTo(id.toString());
        assertThat(finalizada.path("valor").decimalValue()).isEqualByComparingTo("50.000");
        assertThat(finalizada.path("moeda").asText()).isEqualTo("BRL");
        aguardarPublicacoes(id);
        var registro = processamento.queryForMap("SELECT * FROM processamentos WHERE transaction_id = ?", id);
        var saida = processamento.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var historico = transacoes.queryForMap("SELECT * FROM historico_transacoes WHERE transaction_id = ?", id);
        assertThat(registro.get("event_id")).isEqualTo(entrada.get("event_id"));
        assertThat(registro.get("output_event_id")).isEqualTo(saida.get("event_id"));
        assertThat(registro.get("correlation_id")).isEqualTo(id);
        assertThat(registro.get("resultado")).isEqualTo("APROVADA");
        assertThat(historico.get("event_id")).isEqualTo(saida.get("event_id"));
        assertThat(historico.get("causation_id")).isEqualTo(entrada.get("event_id"));
        assertThat(historico.get("correlation_id")).isEqualTo(id);
        assertThat(historico.get("estado_anterior")).isEqualTo("PENDENTE");
        assertThat(historico.get("estado_final")).isEqualTo("APROVADA");
        transacao.put("status", "APROVADA");
        assertThat(transacoes.queryForMap("SELECT * FROM transacoes WHERE id = ?", id))
                .withFailMessage("recuperação alterou dados além do status da transação").isEqualTo(transacao);
        assertThat(transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id))
                .withFailMessage("recuperação alterou a intenção original já publicada").isEqualTo(entrada);
        conferirUnicidade(transacoes, "transacoes", "id", id);
        conferirUnicidade(transacoes, "outbox_eventos", "aggregate_id", id);
        conferirUnicidade(transacoes, "historico_transacoes", "transaction_id", id);
        conferirUnicidade(processamento, "processamentos", "transaction_id", id);
        conferirUnicidade(processamento, "outbox_eventos", "aggregate_id", id);
        aguardarFilasVazias();
    }

    private void aguardarSomenteConsumidorDeResultado() throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var consumidores = consultar("list_consumers");
            if (consumidores.size() == 1 && FILAS.get(2).equals(consumidores.get(0).path("queue_name").asText())
                    && consumidores.get(0).path("ack_required").asBoolean()
                    && consumidores.get(0).path("prefetch_count").asInt() == 1) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("processador parado não deixou somente o consumidor de resultado ativo");
    }

    private void aguardarEntradaPublicadaEPronta(UUID id) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var publicada = transacoes.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1;
            var filas = consultar("list_queues", "name", "messages_ready", "messages_unacknowledged");
            var entrada = porNome(filas, FILAS.get(0));
            if (publicada && entrada.path("messages_ready").asInt() == 1 && entrada.path("messages_unacknowledged").asInt() == 0
                    && FILAS.subList(1, 4).stream().allMatch(nome -> porNome(filas, nome).path("messages_ready").asInt() == 0
                            && porNome(filas, nome).path("messages_unacknowledged").asInt() == 0)) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("evento original não ficou publicado e pronto enquanto processador estava parado");
    }

    @Test
    @Order(4)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void limiteAusente_deveConservarDlqAteReplayConfirmadoDepoisDaCorrecao() throws Exception {
        aguardarFilasVazias();
        var resposta = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50.000,\"moeda\":\"USD\"}")));
        assertThat(resposta.statusCode()).isEqualTo(201);
        var original = JSON.readTree(resposta.body());
        assertThat(original.path("status").asText()).isEqualTo("PENDENTE");
        var id = UUID.fromString(original.path("id").asText());
        var location = resposta.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/transacoes/" + id);
        aguardarEntradaNaDlq(id);
        assertThat(Files.readString(logs.resolve(fase + "-processamento-service.log")))
                .withFailMessage("DLQ não registrou diagnóstico fixo de esgotamento operacional")
                .contains("TransacaoCriada com falha operacional apos 3 tentativas");
        assertThat(aguardarFinal(location, "PENDENTE")).withFailMessage("DLQ alterou GET PENDENTE").isEqualTo(original);
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM processamentos WHERE transaction_id = ?", Integer.class, id)).isZero();
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?", Integer.class, id)).isZero();
        assertThat(transacoes.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?", Integer.class, id)).isZero();
        var entrada = transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var corpo = entrada.get("payload").toString().getBytes(StandardCharsets.UTF_8);
        var antes = bancoInteiro();

        var transacaoAntes = new LinkedHashMap<>(transacoes.queryForMap("SELECT * FROM transacoes WHERE id = ?", id));

        // Confirmar recebimento pelo broker não basta: uma publicação retornada conserva a DLQ.
        assertThat(replayDlq("transacao.criada.replay.sem-rota.v1", id, entrada, corpo)).isFalse();
        aguardarEntradaNaDlq(id);
        assertThat(bancoInteiro()).withFailMessage("replay sem rota alterou os bancos").isEqualTo(antes);

        encerrarProcessos(List.of(processoProcessamento));
        aguardarSomenteConsumidorDeResultado();
        fase++;
        var porta = portaLivre();
        var reiniciado = iniciar("processamento", PROCESSAMENTO, porta, true, true, "--credpay.processamento.limites.USD=100.00");
        aguardarHealth(reiniciado, porta, "processamento com limite corrigido");
        conferirConsumidores();
        assertThat(bancoInteiro()).withFailMessage("reinício sem replay alterou os bancos").isEqualTo(antes);
        aguardarEntradaNaDlq(id);
        assertThat(replayDlq("transacao.criada.v1", id, entrada, corpo)).isTrue();
        var finalizada = aguardarFinal(location, "APROVADA");
        assertThat(finalizada.path("id").asText()).isEqualTo(id.toString());
        assertThat(finalizada.path("valor").decimalValue()).isEqualByComparingTo("50.000");
        assertThat(finalizada.path("moeda").asText()).isEqualTo("USD");
        aguardarPublicacoes(id);
        var registro = processamento.queryForMap("SELECT * FROM processamentos WHERE transaction_id = ?", id);
        var saida = processamento.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var historico = transacoes.queryForMap("SELECT * FROM historico_transacoes WHERE transaction_id = ?", id);
        assertThat(registro.get("event_id")).isEqualTo(entrada.get("event_id"));
        assertThat(registro.get("output_event_id")).isEqualTo(saida.get("event_id"));
        assertThat(registro.get("correlation_id")).isEqualTo(id);
        assertThat(registro.get("resultado")).isEqualTo("APROVADA");
        assertThat(registro.get("moeda")).isEqualTo("USD");
        assertThat((BigDecimal) registro.get("limite_aplicado")).isEqualByComparingTo("100.00");
        assertThat(historico.get("event_id")).isEqualTo(saida.get("event_id"));
        assertThat(historico.get("causation_id")).isEqualTo(entrada.get("event_id"));
        assertThat(historico.get("correlation_id")).isEqualTo(id);
        assertThat(historico.get("estado_anterior")).isEqualTo("PENDENTE");
        assertThat(historico.get("estado_final")).isEqualTo("APROVADA");
        transacaoAntes.put("status", "APROVADA");
        assertThat(transacoes.queryForMap("SELECT * FROM transacoes WHERE id = ?", id))
                .withFailMessage("replay operacional alterou dados além do status").isEqualTo(transacaoAntes);
        assertThat(transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id))
                .withFailMessage("replay operacional alterou outbox original já publicada").isEqualTo(entrada);
        conferirUnicidade(transacoes, "transacoes", "id", id);
        conferirUnicidade(transacoes, "outbox_eventos", "aggregate_id", id);
        conferirUnicidade(transacoes, "historico_transacoes", "transaction_id", id);
        conferirUnicidade(processamento, "processamentos", "transaction_id", id);
        conferirUnicidade(processamento, "outbox_eventos", "aggregate_id", id);
        aguardarFilasVazias();
    }

    private boolean replayDlq(String rota, UUID id, Map<String, Object> entrada, byte[] corpo) throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        factory.setVirtualHost("/");
        factory.setConnectionTimeout(2_000);
        factory.setHandshakeTimeout(10_000);
        factory.setAutomaticRecoveryEnabled(false);
        try (var conexao = factory.newConnection(); var canal = conexao.createChannel()) {
            var mensagem = canal.basicGet(FILAS.get(1), false);
            assertThat(mensagem).withFailMessage("DLQ não entregou a mensagem esperada para replay manual").isNotNull();
            assertThat(mensagem.getBody()).withFailMessage("corpo original mudou na DLQ").isEqualTo(corpo);
            var props = mensagem.getProps();
            assertThat(props.getMessageId()).isEqualTo(entrada.get("event_id").toString());
            assertThat(props.getCorrelationId()).isEqualTo(id.toString());
            assertThat(props.getType()).isEqualTo("TransacaoCriada");
            assertThat(props.getContentType()).isEqualTo("application/json");
            assertThat(props.getContentEncoding()).isEqualTo("UTF-8");
            assertThat(props.getDeliveryMode()).isEqualTo(2);
            assertThat(props.getHeaders().get("x-first-death-reason").toString()).isEqualTo("rejected");
            assertThat(props.getHeaders().get("x-first-death-queue").toString()).isEqualTo(FILAS.get(0));
            var retornada = new AtomicBoolean();
            canal.addReturnListener(retorno -> retornada.set(true));
            canal.confirmSelect();
            canal.basicPublish("credpay.transacoes.v1", rota, true, props, mensagem.getBody());
            canal.waitForConfirmsOrDie(10_000);
            if (retornada.get()) return false;
            canal.basicAck(mensagem.getEnvelope().getDeliveryTag(), false);
            return true;
        }
        // Fechar canal/conexão sem ack conserva/reentrega a mensagem em caso de return, timeout ou falha.
    }

    private void aguardarEntradaNaDlq(UUID id) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            var publicada = transacoes.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1;
            var filas = consultar("list_queues", "name", "messages_ready", "messages_unacknowledged");
            var dlq = porNome(filas, FILAS.get(1));
            if (publicada && dlq.path("messages_ready").asInt() == 1 && dlq.path("messages_unacknowledged").asInt() == 0
                    && List.of(0, 2, 3).stream().allMatch(indice -> porNome(filas, FILAS.get(indice)).path("messages_ready").asInt() == 0
                            && porNome(filas, FILAS.get(indice)).path("messages_unacknowledged").asInt() == 0)) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("evento não ficou publicado e isolado na DLQ de entrada no prazo");
    }

    private List<List<Map<String, Object>>> bancoInteiro() {
        return List.of(transacoes.queryForList("SELECT * FROM transacoes ORDER BY id"),
                transacoes.queryForList("SELECT * FROM historico_transacoes ORDER BY event_id"),
                transacoes.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id"),
                processamento.queryForList("SELECT * FROM processamentos ORDER BY event_id"),
                processamento.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id"));
    }

    private void republicar(Map<String, Object> evento, String exchange, String rota) throws Exception {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(evento.get("event_id").toString());
        properties.setType(evento.get("event_type").toString());
        properties.setCorrelationId(evento.get("aggregate_id").toString());
        var correlacao = new CorrelationData(evento.get("event_id").toString());
        rabbit.send(exchange, rota, new Message(evento.get("payload").toString().getBytes(StandardCharsets.UTF_8), properties), correlacao);
        assertThat(correlacao.getFuture().get(10, TimeUnit.SECONDS).isAck()).as("duplicata confirmada pelo broker").isTrue();
        assertThat(correlacao.getReturned()).withFailMessage("duplicata retornada por ausência de rota").isNull();
    }

    private long aguardarAck(String fila, long minimo) throws Exception {
        var auth = Base64.getEncoder().encodeToString((RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        var endereco = URI.create("http://" + RABBIT.getHost() + ":" + RABBIT.getMappedPort(15672) + "/api/queues/%2F/" + fila);
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            var resposta = enviar(HttpRequest.newBuilder(endereco).header("Authorization", "Basic " + auth).GET());
            assertThat(resposta.statusCode()).as("consulta de contagem de ack do broker descartável").isEqualTo(200);
            var ack = JSON.readTree(resposta.body()).path("message_stats").path("ack");
            if (ack.isIntegralNumber() && ack.longValue() >= minimo) return ack.longValue();
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("contador de ack não alcançou valor mínimo para fila " + fila);
    }

    private void iniciar(boolean topologia, boolean consumirEPublicar) throws Exception {
        fase++;
        portaTransacoes = portaLivre();
        do { portaProcessamento = portaLivre(); } while (portaProcessamento == portaTransacoes);
        var primeiro = iniciar("transacoes", TRANSACOES, portaTransacoes, topologia, consumirEPublicar);
        var segundo = iniciar("processamento", PROCESSAMENTO, portaProcessamento, topologia, consumirEPublicar);
        aguardarHealth(primeiro, portaTransacoes, "transacoes");
        aguardarHealth(segundo, portaProcessamento, "processamento");
    }

    private Process iniciar(String servico, PostgreSQLContainer<?> banco, int porta, boolean topologia,
            boolean consumirEPublicar, String... ajustes) throws Exception {
        var modulo = servico + "-service";
        var jar = RAIZ.resolve(modulo + "/target/" + modulo + "-0.0.1-SNAPSHOT.jar").normalize();
        assertThat(jar.startsWith(RAIZ) && Files.isRegularFile(jar)).as("JAR real compilado no workspace: " + modulo).isTrue();
        Files.createDirectories(logs);
        var argumentos = new ArrayList<>(List.of("java", "-Xmx256m", "-jar", jar.toString(), "--server.port=" + porta,
                "--management.health.rabbit.enabled=true", "--spring.profiles.active=diagnostics",
                "--credpay." + servico + ".consumer.topology.enabled=" + topologia,
                "--credpay." + servico + ".consumer.listener.enabled=" + consumirEPublicar,
                "--credpay.outbox.publisher.enabled=" + consumirEPublicar, "--credpay.processamento.limites.BRL=100.00"));
        argumentos.addAll(List.of(ajustes));
        var processo = new ProcessBuilder(argumentos);
        processo.directory(RAIZ.toFile());
        var ambiente = processo.environment();
        ambiente.put("SPRING_DATASOURCE_URL", banco.getJdbcUrl());
        ambiente.put("SPRING_DATASOURCE_USERNAME", banco.getUsername());
        ambiente.put("SPRING_DATASOURCE_PASSWORD", banco.getPassword());
        ambiente.put("SPRING_RABBITMQ_HOST", RABBIT.getHost());
        ambiente.put("SPRING_RABBITMQ_PORT", RABBIT.getAmqpPort().toString());
        ambiente.put("SPRING_RABBITMQ_USERNAME", RABBIT.getAdminUsername());
        ambiente.put("SPRING_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());
        ambiente.put("SPRING_RABBITMQ_VIRTUAL_HOST", "/");
        var iniciado = processo.redirectErrorStream(true).redirectOutput(logs.resolve(fase + "-" + modulo + ".log").toFile()).start();
        aplicativos.add(iniciado);
        if ("processamento".equals(servico)) processoProcessamento = iniciado;
        return iniciado;
    }

    private void aguardarHealth(Process processo, int porta, String servico) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        do {
            assertThat(processo.isAlive()).as("processo " + servico + " ativo; logs locais em " + logs).isTrue();
            try {
                var resposta = enviar(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/actuator/health")).GET());
                if (resposta.statusCode() == 200 && "UP".equals(JSON.readTree(resposta.body()).path("status").asText())) return;
            } catch (java.io.IOException indisponivelDuranteStartup) { /* startup ainda não abriu HTTP */ }
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("health não ficou UP no prazo para " + servico + "; logs locais em " + logs);
    }

    private void encerrarAplicativos() throws InterruptedException {
        encerrarProcessos(List.copyOf(aplicativos));
    }

    private void encerrarProcessos(List<Process> alvos) throws InterruptedException {
        InterruptedException interrupcao = null;
        for (var processo : alvos) processo.destroy();
        for (var processo : alvos) {
            try {
                if (!processo.waitFor(20, TimeUnit.SECONDS)) {
                    processo.destroyForcibly();
                    processo.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                interrupcao = exception;
                processo.destroyForcibly();
                try { processo.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException repetida) { interrupcao = repetida; }
            } finally {
                if (processo.isAlive()) processo.destroyForcibly();
            }
        }
        aplicativos.removeIf(processo -> !processo.isAlive());
        if (interrupcao != null) {
            Thread.currentThread().interrupt();
            throw interrupcao;
        }
        assertThat(alvos.stream().anyMatch(Process::isAlive)).withFailMessage("processos filhos ainda vivos após cleanup limitado").isFalse();
    }

    private void importar(String servico) throws Exception {
        var resultado = RABBIT.execInContainer("rabbitmqctl", "--timeout", "5", "import_definitions", "/tmp/" + servico + "-policies.json");
        assertThat(resultado.getExitCode()).as("importação das políticas " + servico).isZero();
    }

    private void conferirPreparacao() throws Exception {
        assertThat(porNome(consultar("list_feature_flags", "name", "state"), "stream_queue").path("state").asText()).isEqualTo("enabled");
        var filas = consultar("list_queues", "name", "type", "durable", "arguments", "policy", "operator_policy", "effective_policy_definition");
        var arquivos = List.of("processamento", "transacoes");
        for (int modulo = 0; modulo < arquivos.size(); modulo++) {
            var politicas = JSON.readTree(Files.readString(RAIZ.resolve("infra/rabbitmq/" + arquivos.get(modulo) + "-policies.json"))).path("policies");
            for (int indice = 0; indice < 2; indice++) {
                var fila = porNome(filas, FILAS.get(modulo * 2 + indice));
                assertThat(fila.path("type").asText()).isEqualTo("quorum");
                assertThat(fila.path("durable").asBoolean()).isTrue();
                assertThat(fila.path("arguments")).isEqualTo(JSON.readTree("[[\"x-queue-type\",\"longstr\",\"quorum\"]]"));
                assertThat(fila.path("policy").asText()).isEqualTo(politicas.get(indice).path("name").asText());
                assertThat(fila.path("operator_policy").asText()).isEmpty();
                assertThat(fila.path("effective_policy_definition")).isEqualTo(politicas.get(indice).path("definition"));
            }
        }
        var exchanges = consultar("list_exchanges", "name", "type", "durable");
        for (var nome : List.of("credpay.transacoes.v1", "credpay.processamento.v1", "credpay.transacoes.dlx.v1", "credpay.processamento.dlx.v1")) conferirExchange(exchanges, nome);
        var bindings = consultar("list_bindings", "source_name", "destination_name", "destination_kind", "routing_key");
        conferirBinding(bindings, "credpay.transacoes.v1", FILAS.get(0), "transacao.criada.v1");
        conferirBinding(bindings, "credpay.processamento.dlx.v1", FILAS.get(1), "transacao.criada.dlq.v1");
        conferirBinding(bindings, "credpay.processamento.v1", FILAS.get(2), "transacao.processada.v1");
        conferirBinding(bindings, "credpay.transacoes.dlx.v1", FILAS.get(3), "transacao.processada.dlq.v1");
    }

    private void conferirConsumidores() throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode consumidores;
        do {
            consumidores = consultar("list_consumers");
            var observados = consumidores;
            boolean exatos = observados.size() == 2 && List.of(0, 2).stream().allMatch(indice ->
                    StreamSupport.stream(observados.spliterator(), false).filter(linha ->
                            FILAS.get(indice).equals(linha.path("queue_name").asText())
                            && linha.path("ack_required").asBoolean()
                            && linha.path("prefetch_count").asInt() == (indice == 0 ? 10 : 1)).count() == 1);
            if (exatos) break;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        assertThat(consumidores).hasSize(2);
        for (int indice : List.of(0, 2)) {
            var encontrados = StreamSupport.stream(consumidores.spliterator(), false)
                    .filter(linha -> FILAS.get(indice).equals(linha.path("queue_name").asText())).toList();
            assertThat(encontrados).hasSize(1);
            assertThat(encontrados.getFirst().path("ack_required").asBoolean()).isTrue();
            assertThat(encontrados.getFirst().path("prefetch_count").asInt()).isEqualTo(indice == 0 ? 10 : 1);
        }
    }

    private JsonNode aguardarFinal(String location, String status) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            var resposta = enviar(HttpRequest.newBuilder(uri(location)).GET());
            assertThat(resposta.statusCode()).isEqualTo(200);
            var body = jsonHttpSeguro(resposta.body());
            if (status.equals(body.path("status").asText())) return body;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("GET não alcançou estado final esperado no prazo: " + status);
    }

    private void aguardarPublicacoes(UUID id) throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            boolean primeira = transacoes.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1;
            boolean segunda = processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1;
            if (primeira && segunda) return;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("ambas intenções não ficaram publicadas no prazo");
    }

    private void aguardarFilasVazias() throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var filas = consultar("list_queues", "name", "messages");
            if (FILAS.stream().allMatch(nome -> porNome(filas, nome).path("messages").asInt() == 0)) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("origens/DLQs não ficaram vazias após fluxo final");
    }

    private JsonNode consultar(String comando, String... campos) throws Exception {
        var argumentos = new ArrayList<>(List.of("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json", comando));
        argumentos.addAll(List.of(campos));
        var resultado = RABBIT.execInContainer(argumentos.toArray(String[]::new));
        assertThat(resultado.getExitCode()).as("consulta do broker: " + comando).isZero();
        return JSON.readTree(resultado.getStdout());
    }

    private JsonNode porNome(JsonNode linhas, String nome) {
        var encontrados = StreamSupport.stream(linhas.spliterator(), false).filter(linha -> nome.equals(linha.path("name").asText())).toList();
        assertThat(encontrados).as("recurso exato no broker: " + nome).hasSize(1);
        return encontrados.getFirst();
    }

    private void conferirExchange(JsonNode exchanges, String nome) {
        var exchange = porNome(exchanges, nome);
        assertThat(exchange.path("type").asText()).isEqualTo("direct");
        assertThat(exchange.path("durable").asBoolean()).isTrue();
    }

    private void conferirBinding(JsonNode bindings, String origem, String destino, String rota) {
        assertThat(StreamSupport.stream(bindings.spliterator(), false).filter(linha -> origem.equals(linha.path("source_name").asText())
                && destino.equals(linha.path("destination_name").asText()) && "queue".equals(linha.path("destination_kind").asText())
                && rota.equals(linha.path("routing_key").asText())).count()).isEqualTo(1);
    }

    private void conferirUnicidade(JdbcTemplate banco, String tabela, String coluna, UUID id) {
        assertThat(banco.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE " + coluna + " = ?", Integer.class, id)).isEqualTo(1);
    }

    private JdbcTemplate jdbc(PostgreSQLContainer<?> container) {
        return new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    }

    private int portaLivre() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private URI uri(String caminho) { return URI.create("http://localhost:" + portaTransacoes + caminho); }
    private HttpResponse<String> enviar(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
