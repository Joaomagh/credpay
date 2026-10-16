package br.com.credpay.transacoes.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class EncerramentoCredPayE2E {

    private static final Path RAIZ = Path.of("..").toAbsolutePath().normalize();
    private static final String POSTGRES_IMAGE = "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Container static final PostgreSQLContainer TRANSACOES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("credpay_transacoes_closure").withUsername("test").withPassword("test");
    @Container static final PostgreSQLContainer PROCESSAMENTO = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("credpay_processamento_closure").withUsername("test").withPassword("test");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse(
            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
            .asCompatibleSubstituteFor("rabbitmq"));

    private final Path arquivos = RAIZ.resolve(".local/e2e/closure-" + UUID.randomUUID());
    private final List<Process> processos = new ArrayList<>();
    private LinkedBlockingQueue<String> respostasTransacoes = new LinkedBlockingQueue<>();
    private LinkedBlockingQueue<String> respostasProcessamento = new LinkedBlockingQueue<>();
    private Process aplicativoTransacoes;
    private Process aplicativoProcessamento;
    private JdbcTemplate transacoes;
    private JdbcTemplate processamento;
    private int porta;
    private int portaProcessamento;

    @BeforeAll
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void preparar() throws Exception {
        try {
            Files.createDirectories(arquivos);
            transacoes = jdbc(TRANSACOES);
            processamento = jdbc(PROCESSAMENTO);
            porta = portaLivre();
            do { portaProcessamento = portaLivre(); } while (portaProcessamento == porta);
            aplicativoTransacoes = iniciar("transacoes", "EncerramentoTransacoesFixture",
                    TRANSACOES, porta, respostasTransacoes);
            aplicativoProcessamento = iniciar("processamento", "EncerramentoProcessamentoFixture",
                    PROCESSAMENTO, portaProcessamento, respostasProcessamento);
            aguardar(respostasTransacoes, "READY", 90);
            aguardar(respostasProcessamento, "READY", 90);
            assertThat(transacoes.queryForObject("SELECT to_regclass('public.processamentos')", String.class)).isNull();
            assertThat(processamento.queryForObject("SELECT to_regclass('public.transacoes')", String.class)).isNull();
        } catch (Exception | AssertionError falha) {
            encerrar();
            throw falha;
        }
    }

    @AfterAll
    void encerrar() throws InterruptedException {
        for (var processo : processos) {
            processo.destroy();
            if (!processo.waitFor(10, TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                assertThat(processo.waitFor(10, TimeUnit.SECONDS)).as("processo encerrado").isTrue();
            }
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void pedidoERespostaReais_devemConfirmarFalhaComCadeiaCausal() throws Exception {
        var chave = UUID.randomUUID();
        var pedido = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        var criada = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", chave.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50.000,\"moeda\":\"BRL\"}")));
        assertThat(criada.statusCode()).as("POST real").isEqualTo(201);
        var corpo = JSON.readTree(criada.body());
        assertThat(corpo.path("status").asText()).isEqualTo("PENDENTE");
        var id = UUID.fromString(corpo.path("id").asText());
        var criacao = transacoes.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?", UUID.class, id);
        comando(aplicativoTransacoes, respostasTransacoes, "SOLICITAR " + pedido + " " + id + " " + evidencia);
        comando(aplicativoTransacoes, respostasTransacoes, "PUBLICAR " + id);
        comando(aplicativoProcessamento, respostasProcessamento, "CONSUMIR");
        comando(aplicativoProcessamento, respostasProcessamento, "PUBLICAR " + id);
        comando(aplicativoTransacoes, respostasTransacoes, "CONSUMIR");

        var consulta = enviar(HttpRequest.newBuilder(uri("/transacoes/" + id)).GET());
        assertThat(consulta.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(consulta.body()).path("status").asText())
                .as("pedido/resposta transportados devem confirmar FALHOU").isEqualTo("FALHOU");

        var registro = processamento.queryForMap("SELECT * FROM encerramentos WHERE request_event_id = ?", pedido);
        var resposta = (UUID) registro.get("response_event_id");
        assertThat(registro.get("transaction_id")).isEqualTo(id);
        assertThat(registro.get("creation_event_id")).isEqualTo(criacao);
        assertThat(registro.get("correlation_id")).isEqualTo(id);
        assertThat(registro.get("evidence_id")).isEqualTo(evidencia);
        assertThat(registro.get("resultado")).isEqualTo("CONFIRMADO");
        assertThat(registro.get("output_event_id")).isNull();
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM respostas_encerramento_recebidas
                WHERE event_id = ? AND transaction_id = ? AND creation_event_id = ? AND causation_id = ?
                    AND correlation_id = ? AND resultado = 'CONFIRMADO' AND output_event_id IS NULL
                """, Integer.class, resposta, id, criacao, pedido, id)).isEqualTo(1);
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM historico_transacoes WHERE event_id = ? AND transaction_id = ?
                    AND causation_id = ? AND estado_anterior = 'PENDENTE' AND estado_final = 'FALHOU'
                    AND origem = 'processamento-service/EncerramentoTransacaoRespondido.v1'
                """, Integer.class, resposta, id, pedido)).isEqualTo(1);
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM processamentos WHERE transaction_id = ?",
                Integer.class, id)).isZero();
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                Integer.class, id)).isZero();
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM transacoes t JOIN idempotencias_transacao i ON i.transacao_id = t.id
                WHERE t.id = ? AND i.chave = ? AND t.status = 'FALHOU' AND t.valor = 50 AND scale(t.valor) = 3 AND t.moeda = 'BRL'
                """, Integer.class, id, chave)).as("dados e chave originais preservados").isEqualTo(1);
        conferirInstantesEPedido(id, pedido, evidencia, resposta);
        aguardarFila("pedido", 0, 0);
        aguardarFila("resposta", 0, 0);
        var antes = bancoInteiro();
        comando(aplicativoTransacoes, respostasTransacoes, "SOLICITAR " + pedido + " " + id + " " + evidencia);
        comando(aplicativoTransacoes, respostasTransacoes, "PUBLICAR " + id);
        comando(aplicativoProcessamento, respostasProcessamento, "CONSUMIR");
        comando(aplicativoProcessamento, respostasProcessamento, "PUBLICAR " + id);
        comando(aplicativoTransacoes, respostasTransacoes, "CONSUMIR");
        var replay = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", chave.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50,\"moeda\":\"BRL\"}")));
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).withFailMessage("replay POST alterou resposta original").isEqualTo(criada.body());
        assertThat(bancoInteiro()).withFailMessage("replay alterou snapshot completo dos bancos").isEqualTo(antes);
        assertThat(JSON.readTree(enviar(HttpRequest.newBuilder(uri("/transacoes/" + id)).GET()).body())
                .path("status").asText()).isEqualTo("FALHOU");
        aguardarFila("pedido", 0, 0);
        aguardarFila("resposta", 0, 0);
        System.out.println("Encerramento controlado: POST/PEDIDO/RESPOSTA/GET FALHOU e causalidade comprovados.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"processamento", "transacoes"})
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void quedaDepoisDoCommitAntesDoAck_deveReentregarSemAlterarSnapshot(String servico) throws Exception {
        aguardarFila("pedido", 0, 0);
        aguardarFila("resposta", 0, 0);
        var id = criarPendente();
        var pedido = UUID.randomUUID();
        comando(aplicativoTransacoes, respostasTransacoes, "SOLICITAR " + pedido + " " + id + " " + UUID.randomUUID());
        comando(aplicativoTransacoes, respostasTransacoes, "PUBLICAR " + id);
        boolean processador = "processamento".equals(servico);
        if (!processador) {
            comando(aplicativoProcessamento, respostasProcessamento, "CONSUMIR");
            comando(aplicativoProcessamento, respostasProcessamento, "PUBLICAR " + id);
        }
        var alvo = processador ? aplicativoProcessamento : aplicativoTransacoes;
        var protocolo = processador ? respostasProcessamento : respostasTransacoes;
        var fila = processador ? "pedido" : "resposta";
        escrever(alvo, "CONSUMIR_PAUSADO");
        aguardar(protocolo, "COMMITTED", 20);
        try {
            if (processador) {
                assertThat(processamento.queryForObject("""
                        SELECT COUNT(*) FROM encerramentos e JOIN outbox_encerramentos o
                        ON o.event_id = e.response_event_id AND o.aggregate_id = e.transaction_id
                        WHERE e.request_event_id = ? AND e.transaction_id = ? AND o.published_at IS NULL
                        """, Integer.class, pedido, id)).as("commit externo de registro e outbox").isEqualTo(1);
            } else {
                assertThat(transacoes.queryForObject("""
                        SELECT COUNT(*) FROM transacoes t JOIN historico_transacoes h ON h.transaction_id = t.id
                        JOIN respostas_encerramento_recebidas r ON r.event_id = h.event_id
                        WHERE t.id = ? AND t.status = 'FALHOU' AND r.causation_id = ?
                        """, Integer.class, id, pedido)).as("commit externo de estado, recibo e histórico").isEqualTo(1);
            }
            aguardarFila(fila, 0, 1);
            var antes = bancoInteiro();
            alvo.destroyForcibly();
            assertThat(alvo.waitFor(10, TimeUnit.SECONDS)).as("somente filho da fixture encerrado").isTrue();
            aguardarFila(fila, 1, 0);
            if (processador) {
                respostasProcessamento = new LinkedBlockingQueue<>();
                aplicativoProcessamento = iniciar("processamento", "EncerramentoProcessamentoFixture",
                        PROCESSAMENTO, portaProcessamento, respostasProcessamento);
                aguardar(respostasProcessamento, "READY", 90);
                comando(aplicativoProcessamento, respostasProcessamento, "CONSUMIR");
            } else {
                respostasTransacoes = new LinkedBlockingQueue<>();
                aplicativoTransacoes = iniciar("transacoes", "EncerramentoTransacoesFixture",
                        TRANSACOES, porta, respostasTransacoes);
                aguardar(respostasTransacoes, "READY", 90);
                comando(aplicativoTransacoes, respostasTransacoes, "CONSUMIR");
            }
            aguardarFila(fila, 0, 0);
            assertThat(bancoInteiro()).withFailMessage("reentrega após queda alterou snapshot completo").isEqualTo(antes);
            if (processador) {
                comando(aplicativoProcessamento, respostasProcessamento, "PUBLICAR " + id);
                comando(aplicativoTransacoes, respostasTransacoes, "CONSUMIR");
            }
            var consulta = enviar(HttpRequest.newBuilder(uri("/transacoes/" + id)).GET());
            assertThat(consulta.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(consulta.body()).path("status").asText()).isEqualTo("FALHOU");
            System.out.println("Encerramento controlado: commit externo, queda antes ACK e replay estável em " + servico);
        } finally {
            if (alvo.isAlive()) {
                escrever(alvo, "ACK");
                aguardar(protocolo, "DONE", 20);
            }
        }
    }

    private void conferirInstantesEPedido(UUID id, UUID pedido, UUID evidencia, UUID resposta) {
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM pedidos_encerramento p JOIN outbox_pedidos_encerramento o ON o.event_id = p.event_id
                WHERE p.event_id = ? AND p.transaction_id = ? AND p.evidence_ref = ? AND p.correlation_id = p.transaction_id
                    AND o.published_at IS NOT NULL AND o.payload->>'causationId' = p.creation_event_id::text
                """, Integer.class, pedido, id, evidencia)).as("pedido e outbox causal publicados").isEqualTo(1);
        var instantePedido = transacoes.queryForMap(
                "SELECT occurred_epoch_second, occurred_nano FROM pedidos_encerramento WHERE event_id = ?", pedido);
        var payloadPedido = transacoes.queryForObject(
                "SELECT payload::text FROM outbox_pedidos_encerramento WHERE event_id = ?", String.class, pedido);
        assertThat(java.time.Instant.parse(JSON.readTree(payloadPedido).path("occurredAt").asText()))
                .isEqualTo(java.time.Instant.ofEpochSecond(((Number) instantePedido.get("occurred_epoch_second")).longValue(),
                        ((Number) instantePedido.get("occurred_nano")).longValue()));
        var recebido = processamento.queryForMap(
                "SELECT request_occurred_epoch_second, request_occurred_nano FROM encerramentos WHERE request_event_id = ?", pedido);
        assertThat(recebido.get("request_occurred_epoch_second")).isEqualTo(instantePedido.get("occurred_epoch_second"));
        assertThat(recebido.get("request_occurred_nano")).isEqualTo(instantePedido.get("occurred_nano"));
        var payload = processamento.queryForObject(
                "SELECT payload::text FROM outbox_encerramentos WHERE event_id = ? AND published_at IS NOT NULL",
                String.class, resposta);
        var instante = java.time.Instant.parse(JSON.readTree(payload).path("occurredAt").asText());
        assertThat(JSON.readTree(payload).path("data").has("outputEventId")).isFalse();
        assertThat(processamento.queryForObject("""
                SELECT COUNT(*) FROM encerramentos e JOIN outbox_encerramentos o ON o.event_id = e.response_event_id
                WHERE e.response_event_id = ? AND o.occurred_at = e.responded_at
                """, Integer.class, resposta)).isEqualTo(1);
        assertThat(processamento.queryForObject(
                "SELECT responded_at FROM encerramentos WHERE response_event_id = ?", java.sql.Timestamp.class, resposta).toInstant())
                .isEqualTo(instante);
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM respostas_encerramento_recebidas r JOIN historico_transacoes h ON h.event_id = r.event_id
                WHERE r.event_id = ? AND r.occurred_epoch_second = ? AND r.occurred_nano = ?
                    AND h.occurred_at_epoch_second = r.occurred_epoch_second AND h.occurred_at_nano = r.occurred_nano
                """, Integer.class, resposta, instante.getEpochSecond(), instante.getNano()))
                .as("instante da resposta preservado no recibo e histórico").isEqualTo(1);
    }

    private UUID criarPendente() throws Exception {
        var criada = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50.00,\"moeda\":\"BRL\"}")));
        assertThat(criada.statusCode()).isEqualTo(201);
        var corpo = JSON.readTree(criada.body());
        assertThat(corpo.path("status").asText()).isEqualTo("PENDENTE");
        return UUID.fromString(corpo.path("id").asText());
    }

    private Map<String, List<String>> bancoInteiro() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (var tabela : List.of("transacoes", "idempotencias_transacao", "outbox_eventos", "pedidos_encerramento",
                "outbox_pedidos_encerramento", "respostas_encerramento_recebidas", "historico_transacoes")) {
            snapshot.put("transacoes/" + tabela,
                    transacoes.queryForList("SELECT to_jsonb(t)::text FROM " + tabela + " t ORDER BY to_jsonb(t)::text", String.class));
        }
        for (var tabela : List.of("processamentos", "outbox_eventos", "encerramentos", "criacao_bloqueada_recebimentos")) {
            snapshot.put("processamento/" + tabela,
                    processamento.queryForList("SELECT to_jsonb(t)::text FROM " + tabela + " t ORDER BY to_jsonb(t)::text", String.class));
        }
        snapshot.put("processamento/outbox_encerramentos", processamento.queryForList(
                "SELECT to_jsonb(t)::text FROM outbox_encerramentos t ORDER BY to_jsonb(t)::text", String.class));
        return snapshot;
    }

    private void aguardarFila(String rota, int ready, int unacked) throws Exception {
        int ultimoReady = -1;
        int ultimoUnacked = -1;
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            var resultado = RABBIT.execInContainer("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json",
                    "list_queues", "name", "messages_ready", "messages_unacknowledged");
            assertThat(resultado.getExitCode()).as("consulta de contadores do broker").isZero();
            var filas = JSON.readTree(resultado.getStdout());
            var encontrados = StreamSupport.stream(filas.spliterator(), false)
                    .filter(linha -> ("credpay.fixture." + rota + ".v1").equals(linha.path("name").asText())).toList();
            assertThat(encontrados).as("fila exata da fixture").hasSize(1);
            ultimoReady = encontrados.getFirst().path("messages_ready").asInt(-1);
            ultimoUnacked = encontrados.getFirst().path("messages_unacknowledged").asInt(-1);
            if (ultimoReady == ready && ultimoUnacked == unacked) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("fila " + rota + ": esperado ready=" + ready + "/unacked=" + unacked
                + ", observado ready=" + ultimoReady + "/unacked=" + ultimoUnacked);
    }

    private Process iniciar(String servico, String classe, PostgreSQLContainer banco, int httpPort,
            LinkedBlockingQueue<String> respostas) throws Exception {
        var modulo = servico + "-service";
        var nome = "br.com.credpay." + servico + ".infrastructure.messaging." + classe;
        var relativa = nome.replace('.', '/') + ".class";
        var jar = RAIZ.resolve(modulo + "/target/" + modulo + "-0.0.1-SNAPSHOT.jar");
        var selecionadas = arquivos.resolve(servico);
        var destino = selecionadas.resolve(relativa);
        Files.createDirectories(destino.getParent());
        Files.copy(RAIZ.resolve(modulo + "/target/test-classes/" + relativa), destino, StandardCopyOption.REPLACE_EXISTING);
        try (var entradas = Files.walk(selecionadas); var zip = new JarFile(jar.toFile())) {
            assertThat(entradas.filter(Files::isRegularFile).toList()).containsExactly(destino);
            assertThat(zip.stream().anyMatch(e -> e.getName().contains(classe))).as("fixture ausente do JAR").isFalse();
        }
        var builder = new ProcessBuilder("java", "-Xmx256m", "-Dloader.main=" + nome,
                "-Dloader.path=" + selecionadas, "-cp", jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "--server.port=" + httpPort,
                "--management.health.rabbit.enabled=true", "--credpay.processamento.limites.BRL=100.00",
                "--credpay." + servico + ".consumer.topology.enabled=false",
                "--credpay." + servico + ".consumer.listener.enabled=false", "--credpay.outbox.publisher.enabled=false");
        builder.directory(RAIZ.toFile());
        var ambiente = builder.environment();
        ambiente.put("SPRING_DATASOURCE_URL", banco.getJdbcUrl());
        ambiente.put("SPRING_DATASOURCE_USERNAME", banco.getUsername());
        ambiente.put("SPRING_DATASOURCE_PASSWORD", banco.getPassword());
        ambiente.put("SPRING_RABBITMQ_HOST", RABBIT.getHost());
        ambiente.put("SPRING_RABBITMQ_PORT", RABBIT.getAmqpPort().toString());
        ambiente.put("SPRING_RABBITMQ_USERNAME", RABBIT.getAdminUsername());
        ambiente.put("SPRING_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());
        ambiente.put("SPRING_RABBITMQ_VIRTUAL_HOST", "/");
        var processo = builder.redirectErrorStream(true).start();
        processos.add(processo);
        var arquivoLog = arquivos.resolve(servico + "-" + processos.size() + ".log");
        Thread.ofPlatform().daemon().start(() -> {
            try (var leitor = new BufferedReader(new InputStreamReader(processo.getInputStream(), StandardCharsets.UTF_8));
                    var log = Files.newBufferedWriter(arquivoLog, StandardCharsets.UTF_8)) {
                String linha;
                while ((linha = leitor.readLine()) != null) {
                    if (linha.startsWith("CREDPAY_FIXTURE ")) respostas.offer(linha.substring(16));
                    else { log.write(linha); log.newLine(); }
                }
            } catch (java.io.IOException falha) {
                respostas.offer("FAILURE");
            }
        });
        return processo;
    }

    private void comando(Process processo, LinkedBlockingQueue<String> respostas, String comando) throws Exception {
        escrever(processo, comando);
        aguardar(respostas, "DONE", 20);
    }

    private void escrever(Process processo, String comando) throws java.io.IOException {
        assertThat(processo.isAlive()).as("fixture ativa").isTrue();
        var escritor = new OutputStreamWriter(processo.getOutputStream(), StandardCharsets.UTF_8);
        escritor.write(comando + "\n");
        escritor.flush();
    }

    private void aguardar(LinkedBlockingQueue<String> respostas, String esperado, int segundos) throws InterruptedException {
        assertThat(respostas.poll(segundos, TimeUnit.SECONDS))
                .withFailMessage("fixture não confirmou etapa %s; diagnóstico local em %s", esperado, arquivos)
                .isEqualTo(esperado);
    }

    private HttpResponse<String> enviar(HttpRequest.Builder builder) throws Exception {
        return HTTP.send(builder.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String caminho) { return URI.create("http://localhost:" + porta + caminho); }

    private static JdbcTemplate jdbc(PostgreSQLContainer container) {
        var fonte = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        return new JdbcTemplate(fonte);
    }

    private int portaLivre() throws java.io.IOException {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
