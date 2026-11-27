package br.com.credpay.transacoes.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

abstract class CorpoJsonLimitadoAdvice extends RequestBodyAdviceAdapter {

    private static final int LIMITE_CORPO_BYTES = 8192;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.hasParameterAnnotation(RequestBody.class);
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

    private static final class CorpoAcimaDoLimiteException extends RuntimeException {
    }
}
