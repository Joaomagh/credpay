# Guia repetível — TDD em Java

Use para comportamento de negócio e correção de bug. Ciclo: **um comportamento por vez**.

1. Nomeie o teste como `metodo_deveResultado_quandoCondicao`.
2. Organize em `given/when/then`; observe resultado/estado, não detalhe interno.
3. Execute apenas o teste e confirme **red pelo motivo esperado**.
4. Implemente o mínimo; execute teste focado e suíte afetada até **green**.
5. Refatore nomes/duplicação; rode novamente e registre a regra no `spec.md`.

Exemplo mínimo (`src/test/java/.../TransacaoTest.java`):

```java
class TransacaoTest {
    @Test
    void criar_deveDefinirStatusPendente_quandoValorForPositivo() {
        var valor = new BigDecimal("10.00");

        var transacao = Transacao.criar(valor, Currency.getInstance("BRL"));

        assertThat(transacao.getStatus()).isEqualTo(Status.PENDENTE);
    }
}
```

**Red:** `Transacao.criar`/comportamento ainda não existe; confirmar a falha focada.  
**Green mínimo:**

```java
final class Transacao {
    private final Status status;
    private Transacao(Status status) { this.status = status; }
    static Transacao criar(BigDecimal valor, Currency moeda) {
        return new Transacao(Status.PENDENTE);
    }
    Status getStatus() { return status; }
}
```

**Refactor:** melhorar nomes e só extrair value object quando outro teste provar a necessidade. Mockito é reservado a colaboradores externos:

```java
var gateway = mock(EventPublisher.class);
verify(gateway).publicar(evento);
```

Não use Mockito para testar getters, entidades ou a própria regra. O exemplo é didático: validação de valor/moeda nasce em testes seguintes, nunca antecipada.
