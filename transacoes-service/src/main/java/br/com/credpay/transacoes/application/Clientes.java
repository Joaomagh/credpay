package br.com.credpay.transacoes.application;

import java.util.UUID;
import java.util.List;

public interface Clientes {

    Resultado cadastrar(UUID contaId, String nome);

    Resultado buscar(UUID contaId, UUID clienteId);

    Pagina listar(UUID contaId, int limite, UUID apos);

    record Resultado(UUID id, String nome) {
    }

    record Pagina(List<Resultado> itens, UUID proximo) {

        public Pagina {
            itens = List.copyOf(itens);
        }
    }
}
