package io.github.seuh.esig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedJson() {
        log.debug("Rejected request: malformed JSON");
        return error(HttpStatus.BAD_REQUEST, "Malformed JSON request");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> missingFile() {
        log.debug("Rejected request: missing multipart file");
        return error(HttpStatus.BAD_REQUEST, "A file part is required");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        log.debug("Rejected request: upload too large");
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Document exceeds the upload limit");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> malformedMultipart() {
        log.debug("Rejected request: malformed multipart body");
        return error(HttpStatus.BAD_REQUEST, "Malformed multipart request");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedContentType() {
        log.debug("Rejected request: unsupported content type");
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type");
    }

    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidDocumentException e) {
        log.debug("Rejected request: invalid document");
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(TrustUnavailableException.class)
    public ResponseEntity<Map<String, String>> trustUnavailable(TrustUnavailableException e) {
        log.debug("Rejected request: trust data unavailable");
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
