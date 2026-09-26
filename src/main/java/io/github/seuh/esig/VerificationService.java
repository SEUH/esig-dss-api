package io.github.seuh.esig;

import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.SignatureQualification;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

@Service
public class VerificationService {
    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    static final int MAX_DOCUMENT_BYTES = 25 * 1024 * 1024;
    private final TrustLists trustLists;

    public VerificationService(TrustLists trustLists) {
        this.trustLists = trustLists;
    }

    public VerificationResult verify(byte[] bytes, String filename) {
        long started = System.nanoTime();
        if (bytes == null || bytes.length == 0) {
            throw new InvalidDocumentException("Document is empty");
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new InvalidDocumentException("Document exceeds the 25 MiB limit");
        }
        // Refuse to imply a QES result unless trusted lists are loaded.
        var verifier = trustLists.verifier();
        try {
            SignedDocumentValidator validator = SignedDocumentValidator.fromDocument(
                    new InMemoryDocument(bytes, filename == null || filename.isBlank() ? "document" : filename));
            validator.setCertificateVerifier(verifier);
            Reports reports = validator.validateDocument();
            SimpleReport simple = reports.getSimpleReport();
            DiagnosticData diagnostic = reports.getDiagnosticData();
            List<SignatureResult> signatures = new ArrayList<>();
            for (String id : simple.getSignatureIdList()) {
                Indication indication = simple.getIndication(id);
                SignatureQualification qualification = simple.getSignatureQualification(id);
                List<CertificateData> chain = new ArrayList<>();
                List<CertificateWrapper> certificateChain = diagnostic.getSignatureCertificateChain(id);
                for (CertificateWrapper certificate : certificateChain) {
                    chain.add(certificate(certificate));
                }
                boolean qesValid = indication == Indication.TOTAL_PASSED
                        && qualification == SignatureQualification.QESIG;
                log.debug("Signature result: indication={}, subIndication={}, qualification={}, chainLength={}",
                        indication, simple.getSubIndication(id), qualification, chain.size());
                if (qesValid) {
                    trustLists.rememberChain(certificateChain);
                }
                signatures.add(new SignatureResult(id, simple.getSignedBy(id),
                        instant(diagnostic.getSignatureDate(id)),
                        indication == null ? null : indication.name(),
                        simple.getSubIndication(id) == null ? null : simple.getSubIndication(id).name(),
                        qualification == null ? null : qualification.name(),
                        qesValid,
                        chain));
            }
            long qesCount = signatures.stream().filter(SignatureResult::qesValid).count();
            log.info("Verification completed: bytes={}, signatures={}, qes={}, durationMs={}",
                    bytes.length, signatures.size(), qesCount, elapsedMillis(started));
            return new VerificationResult(signatures.size(), signatures);
        } catch (TrustUnavailableException e) {
            throw e;
        } catch (DSSException | IllegalArgumentException e) {
            log.debug("Document validation rejected: exceptionType={}, bytes={}, durationMs={}",
                    e.getClass().getSimpleName(), bytes.length, elapsedMillis(started));
            throw new InvalidDocumentException("Unable to validate the supplied signed document", e);
        }
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static CertificateData certificate(CertificateWrapper certificate) {
        byte[] der = certificate.getBinaries();
        return new CertificateData(certificate.getCertificateDN(),
                certificate.getCertificateIssuerDN(), certificate.getSerialNumber(),
                instant(certificate.getNotBefore()), instant(certificate.getNotAfter()),
                der == null ? null : Base64.getEncoder().encodeToString(der));
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    public record VerificationResult(int signatureCount, List<SignatureResult> signatures) {}

    public record SignatureResult(String id, String signedBy, Instant claimedSigningTime,
                                  String indication, String subIndication, String qualification,
                                  boolean qesValid, List<CertificateData> certificateChain) {}

    public record CertificateData(String subject, String issuer, String serialNumber,
                                  Instant notBefore, Instant notAfter, String derBase64) {}
}
