package br.com.credpay.transacoes.infrastructure.messaging;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import br.com.credpay.transacoes.TransacoesServiceApplication;
import br.com.credpay.transacoes.application.SolicitarEncerramentoService;
import br.com.credpay.transacoes.application.AplicarEncerramentoService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Entrada somente de teste; nenhuma anotação torna esta classe componente da aplicação. */
public final class EncerramentoTransacoesFixture {

    private EncerramentoTransacoesFixture() { }

    public static void main(String[] args) {
        try (var contexto = new SpringApplicationBuilder(TransacoesServiceApplication.class)
                .initializers(context -> {
                    var registro = (BeanDefinitionRegistry) context.getBeanFactory();
                    if (registro.containsBeanDefinition("fixtureSolicitarEncerramentoService")) throw new IllegalStateException();
                    registro.registerBeanDefinition("fixtureSolicitarEncerramentoService", new RootBeanDefinition(SolicitarEncerramentoService.class));
                    if (registro.containsBeanDefinition("fixtureAplicarEncerramentoService")) throw new IllegalStateException();
                    registro.registerBeanDefinition("fixtureAplicarEncerramentoService", new RootBeanDefinition(AplicarEncerramentoService.class));
                }).run(args)) {
            if (!AopUtils.isAopProxy(contexto.getBean(SolicitarEncerramentoService.class))) throw new IllegalStateException();
            if (!AopUtils.isAopProxy(contexto.getBean(AplicarEncerramentoService.class))) throw new IllegalStateException();
            var parser = new EncerramentoTransacaoRespondidoMessageParser(contexto.getBean(ObjectMapper.class));
            if (parser.getClass().getClassLoader() != TransacoesServiceApplication.class.getClassLoader()) {
                throw new IllegalStateException();
            }
            System.out.println("CREDPAY_FIXTURE READY");
            var leitor = new InputStreamReader(System.in, StandardCharsets.UTF_8);
            String linha;
            while ((linha = lerLimitado(leitor)) != null) {
                executar(contexto, linha);
                System.out.println("CREDPAY_FIXTURE DONE");
            }
        } catch (Exception | AssertionError falha) {
            System.err.println("CREDPAY_FIXTURE FAILURE");
            System.exit(2);
        }
    }

    private static void executar(ConfigurableApplicationContext contexto, String linha) {
        var partes = linha.split(" ", -1);
        if (partes.length == 4 && "SOLICITAR".equals(partes[0])) {
            contexto.getBean(SolicitarEncerramentoService.class)
                    .executar(uuid(partes[1]), uuid(partes[2]), uuid(partes[3]));
            return;
        }
        if (partes.length == 2 && "PUBLICAR".equals(partes[0])) {
            uuid(partes[1]);
            // Scaffolding do primeiro red: transporte ainda não implementado.
            return;
        }
        if (partes.length == 1 && "CONSUMIR".equals(partes[0])) {
            // Scaffolding do primeiro red: nenhuma resposta aplicada.
            return;
        }
        throw new IllegalArgumentException();
    }

    private static UUID uuid(String texto) {
        var id = UUID.fromString(texto);
        if (!id.toString().equals(texto)) throw new IllegalArgumentException();
        return id;
    }

    private static String lerLimitado(InputStreamReader leitor) throws java.io.IOException {
        var linha = new StringBuilder();
        int caractere;
        while ((caractere = leitor.read()) != -1) {
            if (caractere == '\n') return linha.toString();
            if (caractere == '\r' || linha.length() >= 160) throw new IllegalArgumentException();
            linha.append((char) caractere);
        }
        if (!linha.isEmpty()) throw new IllegalArgumentException();
        return null;
    }
}
