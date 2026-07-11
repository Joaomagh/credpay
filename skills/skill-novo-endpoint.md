# Guia repetível — Novo endpoint REST

Não crie todas as camadas por obrigação. Comece pelo contrato e deixe cada teste revelar a próxima colaboração.

Ordem por slice:

1. Definir método/rota, request, response, erros e critério no `spec.md`.
2. Criar `@WebMvcTest` do controller (**red**) para status, JSON e validação.
3. Implementar Controller/DTO mínimos (**green**), delegando caso de uso.
4. Criar teste unitário do Service/caso de uso (**red**); implementar regra mínima.
5. Se persistência for necessária, definir a porta Repository e criar adapter JPA + migration.
6. Criar teste de integração com PostgreSQL real; depois, teste HTTP do slice completo.

Estrutura dentro de cada serviço:

```text
src/main/java/br/com/credpay/<servico>/
├── api/             # controllers, requests, responses, advice
├── application/     # casos de uso e portas
├── domain/          # regras sem Spring/JPA quando viável
└── infrastructure/  # JPA, RabbitMQ e configuração
src/test/java/...    # espelha os pacotes relevantes
src/main/resources/db/migration/
```

Teste inicial mínimo:

```java
@WebMvcTest(TransacaoController.class)
class TransacaoControllerTest {
    @Autowired MockMvc mvc;
    @MockBean CriarTransacao criarTransacao;

    @Test
    void criar_deveRetornar202_quandoPedidoForValido() throws Exception {
        when(criarTransacao.executar(any())).thenReturn(new Criacao("tx-1", "PENDENTE"));

        mvc.perform(post("/transacoes")
                .contentType(APPLICATION_JSON)
                .content("""{"valor":10.00,"moeda":"BRL"}"""))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDENTE"));
    }
}
```

Antes de concluir: validar erro 400, contrato OpenAPI, migration/constraint, teste de integração e ausência de entidade JPA no JSON. Não criar Repository se o endpoint não persiste.
