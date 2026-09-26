package io.github.seuh.esig;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

@RestController
public class VerificationController {
    private final VerificationService verification;

    public VerificationController(VerificationService verification) {
        this.verification = verification;
    }

    @PostMapping(path = "/api/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VerificationService.VerificationResult form(@RequestParam("file") MultipartFile file) throws IOException {
        return verification.verify(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping(path = "/api/verify", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public VerificationService.VerificationResult binary(
            @RequestHeader(name = "X-Filename", required = false) String filename,
            HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > VerificationService.MAX_DOCUMENT_BYTES) {
            throw new InvalidDocumentException("Document exceeds the 25 MiB limit");
        }
        byte[] bytes = request.getInputStream().readNBytes(VerificationService.MAX_DOCUMENT_BYTES + 1);
        if (bytes.length > VerificationService.MAX_DOCUMENT_BYTES) {
            throw new InvalidDocumentException("Document exceeds the 25 MiB limit");
        }
        return verification.verify(bytes, filename);
    }

    @PostMapping(path = "/api/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VerificationService.VerificationResult base64(@RequestBody Base64Document document) {
        if (document == null || document.base64() == null) {
            throw new InvalidDocumentException("base64 is required");
        }
        try {
            return verification.verify(Base64.getDecoder().decode(document.base64()), document.filename());
        } catch (IllegalArgumentException e) {
            throw new InvalidDocumentException("base64 must contain valid Base64 data", e);
        }
    }

    public record Base64Document(String filename, String base64) {}
}
