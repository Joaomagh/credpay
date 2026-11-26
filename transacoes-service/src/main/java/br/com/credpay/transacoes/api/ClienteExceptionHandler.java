package br.com.credpay.transacoes.api;

import br.com.credpay.transacoes.application.ClienteNaoEncontradoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ClienteController.class)
class ClienteExceptionHandler extends CorpoJsonLimitadoAdvice {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail tratarCorpoIlegivel() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "corpo deve conter somente nome textual em JSON válido");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail tratarClienteInvalido(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    }

    @ExceptionHandler(RequisicaoClienteInvalidaException.class)
    ProblemDetail tratarRequisicaoInvalida(RequisicaoClienteInvalidaException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ClienteNaoEncontradoException.class)
    ProblemDetail tratarClienteNaoEncontrado(ClienteNaoEncontradoException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }
}
