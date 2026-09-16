package br.com.credpay.transacoes.api;

import br.com.credpay.transacoes.application.ConflitoIdempotenciaException;
import br.com.credpay.transacoes.application.TransacaoNaoEncontradaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = TransacaoController.class)
class TransacaoExceptionHandler {

    @ExceptionHandler(IdempotencyKeyInvalidaException.class)
    ProblemDetail tratarChaveIdempotenciaInvalida(IdempotencyKeyInvalidaException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Requisição inválida");
        return problem;
    }

    @ExceptionHandler(ConflitoIdempotenciaException.class)
    ProblemDetail tratarConflitoIdempotencia(ConflitoIdempotenciaException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Conflito de idempotência");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail tratarTipoDeArgumentoInvalido() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "id deve ser um UUID válido");
        problem.setTitle("Requisição inválida");
        return problem;
    }

    @ExceptionHandler(TransacaoNaoEncontradaException.class)
    ProblemDetail tratarTransacaoNaoEncontrada(TransacaoNaoEncontradaException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Transação não encontrada");
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail tratarCorpoIlegivel() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "corpo deve conter um JSON válido com valor numérico e moeda textual");
        problem.setTitle("Requisição inválida");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail tratarArgumentoInvalido(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        problem.setTitle("Transação inválida");
        return problem;
    }
}
