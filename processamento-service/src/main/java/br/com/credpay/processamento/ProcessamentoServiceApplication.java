package br.com.credpay.processamento;

import br.com.credpay.processamento.infrastructure.configuration.LimitesProcessamentoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LimitesProcessamentoProperties.class)
public class ProcessamentoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessamentoServiceApplication.class, args);
    }
}
