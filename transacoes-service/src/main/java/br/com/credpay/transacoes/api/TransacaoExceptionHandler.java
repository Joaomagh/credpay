package br.com.credpay.transacoes.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TransacaoController.class)
class TransacaoExceptionHandler {

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
