package br.com.credpay.transacoes.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RegistroContaController.class)
class RegistroContaExceptionHandler extends CorpoJsonLimitadoAdvice {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail corpoInvalido() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Informe somente e-mail e senha em JSON válido.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail dadosInvalidos(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    }
}
