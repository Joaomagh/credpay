package br.com.credpay.transacoes.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TransacaoController.class)
class TransacaoExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail tratarArgumentoInvalido(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        problem.setTitle("Transação inválida");
        return problem;
    }
}
