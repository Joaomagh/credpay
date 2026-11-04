package br.com.credpay.transacoes.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;

import br.com.credpay.transacoes.application.ConflitoIdempotenciaException;
import br.com.credpay.transacoes.application.TransacaoNaoEncontradaException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@RestControllerAdvice(assignableTypes = TransacaoController.class)
class TransacaoExceptionHandler extends RequestBodyAdviceAdapter {

    private static final int LIMITE_CORPO_BYTES = 8192;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == TransacaoController.TransacaoRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        var headers = input.getHeaders();
        if (headers.getContentLength() > LIMITE_CORPO_BYTES) {
            throw new CorpoAcimaDoLimiteException();
        }
        var corpo = input.getBody().readNBytes(LIMITE_CORPO_BYTES + 1);
        if (corpo.length > LIMITE_CORPO_BYTES) {
            throw new CorpoAcimaDoLimiteException();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(corpo);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    @ExceptionHandler(CorpoAcimaDoLimiteException.class)
    ProblemDetail tratarCorpoAcimaDoLimite() {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONTENT_TOO_LARGE, "corpo da requisição excede " + LIMITE_CORPO_BYTES + " bytes");
    }

    @ExceptionHandler(IdempotencyKeyInvalidaException.class)
    ProblemDetail tratarChaveIdempotenciaInvalida(IdempotencyKeyInvalidaException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Requisição inválida");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler(ConflitoIdempotenciaException.class)
    ProblemDetail tratarConflitoIdempotencia(ConflitoIdempotenciaException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Conflito de idempotência");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IdTransacaoInvalidoException.class})
    ProblemDetail tratarTipoDeArgumentoInvalido() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "id deve ser um UUID válido");
        problem.setTitle("Requisição inválida");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler(TransacaoNaoEncontradaException.class)
    ProblemDetail tratarTransacaoNaoEncontrada(TransacaoNaoEncontradaException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Transação não encontrada");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail tratarCorpoIlegivel() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "corpo deve conter um JSON válido com valor numérico e moeda textual");
        problem.setTitle("Requisição inválida");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail tratarArgumentoInvalido(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        problem.setTitle("Transação inválida");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    private static final class CorpoAcimaDoLimiteException extends RuntimeException {
    }
}
