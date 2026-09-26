package io.github.seuh.esig;

import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlByteRange;
import eu.europa.esig.dss.diagnostic.jaxb.XmlModification;
import eu.europa.esig.dss.diagnostic.jaxb.XmlModificationDetection;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModification;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModifications;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFRevision;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFSignatureDictionary;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFSignatureField;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignature;
import eu.europa.esig.dss.pdf.PdfDocumentReader;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfContractIntegrityTest {
    @Test
    void emptyAndNonPdfDocumentsFailClosed() {
        assertEquals("NON_PDF", PdfContractIntegrity.assess(null, null, List.of(), List.of())
                .issues().getFirst().code());
        assertEquals("NO_SIGNATURES", PdfContractIntegrity.assess(
                new PdfContractIntegrity.CompleteDifferencesFinder(),
                new PdfContractIntegrity.CompleteObjectModificationsFinder(), List.of(), List.of())
                .issues().getFirst().code());
    }

    @Test
    void validSignatureWithCompletedComparisonPasses() {
        var assessment = assess(List.of(result("one", "TOTAL_PASSED")), List.of(revision("one")));
        assertTrue(assessment.passed());
        assertTrue(assessment.issues().isEmpty());
    }

    @Test
    void invalidSignatureFailsEvenIfContentIsUnchanged() {
        var assessment = assess(List.of(result("one", "TOTAL_FAILED")), List.of(revision("one")));
        assertFalse(assessment.passed());
        assertEquals("SIGNATURE_INVALID", assessment.issues().getFirst().code());
    }

    @Test
    void pageAndFormChangesFail() {
        XmlSignature pageChanged = revision("first");
        pageChanged.getPDFRevision().getModificationDetection().getPageDifference().add(new XmlModification());
        XmlSignature formChanged = revision("second");
        XmlObjectModification change = new XmlObjectModification();
        change.setFieldName("contractPrice");
        formChanged.getPDFRevision().getModificationDetection().getObjectModifications()
                .getSignatureOrFormFill().add(change);
        var assessment = assess(List.of(result("first", "TOTAL_PASSED"), result("second", "TOTAL_PASSED")),
                List.of(pageChanged, formChanged));
        assertFalse(assessment.passed());
        assertEquals(List.of("CONTENT_CHANGED", "CONTENT_CHANGED"),
                assessment.issues().stream().map(VerificationService.IntegrityIssue::code).toList());
    }

    @Test
    void unsignedAnnotationAndUndefinedEditsAfterLastSignatureFail() {
        XmlSignature signature = revision("last");
        XmlObjectModifications changes = signature.getPDFRevision().getModificationDetection()
                .getObjectModifications();
        changes.getAnnotationChanges().add(new XmlObjectModification());
        changes.getUndefined().add(new XmlObjectModification());
        var assessment = assess(List.of(result("last", "TOTAL_PASSED")), List.of(signature));
        assertFalse(assessment.passed());
        assertEquals("CONTENT_CHANGED", assessment.issues().getFirst().code());
    }

    @Test
    void omittedEmptyModificationDetailsDoNotLookLikeMissingComparison() {
        XmlSignature signature = revision("one");
        signature.getPDFRevision().setModificationDetection(null);
        var assessment = assess(List.of(result("one", "TOTAL_PASSED")), List.of(signature));
        assertTrue(assessment.passed());
    }

    @Test
    void missingObjectComparisonFailsClosed() {
        var assessment = PdfContractIntegrity.assess(new PdfContractIntegrity.CompleteDifferencesFinder(),
                new PdfContractIntegrity.CompleteObjectModificationsFinder(),
                List.of(result("one", "TOTAL_PASSED")), List.of(new SignatureWrapper(revision("one"))));
        assertFalse(assessment.passed());
        assertEquals("COMPARISON_INCOMPLETE", assessment.issues().getFirst().code());
    }

    @Test
    void laterSignatureFieldIsAllowedButUnknownChangeFailsClosed() {
        XmlSignature first = revision("first");
        XmlSignature second = revision("second");
        second.getPDFRevision().getPDFSignatureDictionary().getSignatureByteRange()
                .getValue().set(2, BigInteger.valueOf(40));
        XmlPDFSignatureField signatureField = new XmlPDFSignatureField();
        signatureField.setName("secondSignature");
        second.getPDFRevision().getFields().add(signatureField);
        XmlObjectModification signatureChange = new XmlObjectModification();
        signatureChange.setFieldName("secondSignature");
        first.getPDFRevision().getModificationDetection().getObjectModifications()
                .getSignatureOrFormFill().add(signatureChange);
        var results = List.of(result("first", "TOTAL_PASSED"), result("second", "TOTAL_PASSED"));
        assertTrue(assess(results, List.of(first, second)).passed());

        signatureChange.setFieldName(null);
        var incomplete = assess(results, List.of(first, second));
        assertFalse(incomplete.passed());
        assertEquals("COMPARISON_INCOMPLETE", incomplete.issues().getFirst().code());
    }

    @Test
    void visualComparisonChecksPageElevenAndRecordsRenderingFailure() throws IOException {
        var finder = new PdfContractIntegrity.CompleteDifferencesFinder();
        AtomicInteger lastPage = new AtomicInteger();
        PdfDocumentReader signed = reader(lastPage, false);
        PdfDocumentReader finalPdf = reader(new AtomicInteger(), false);
        assertTrue(finder.getVisualDifferences(signed, finalPdf).isEmpty());
        assertEquals(11, lastPage.get());
        assertTrue(finder.complete());

        finder.getVisualDifferences(reader(new AtomicInteger(), true), finalPdf);
        assertFalse(finder.complete());
    }

    private static PdfDocumentReader reader(AtomicInteger lastPage, boolean failLastPage) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        return (PdfDocumentReader) Proxy.newProxyInstance(PdfDocumentReader.class.getClassLoader(),
                new Class<?>[] {PdfDocumentReader.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getNumberOfPages" -> 11;
                        case "getPdfAnnotations" -> List.of();
                        case "generateImageScreenshot", "generateImageScreenshotWithoutAnnotations" -> {
                            int page = (int) args[0];
                            lastPage.set(page);
                            if (failLastPage && page == 11) {
                                throw new IOException("render failed");
                            }
                            yield image;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static VerificationService.ContractIntegrity assess(
            List<VerificationService.SignatureResult> results, List<XmlSignature> signatures) {
        var objectFinder = new PdfContractIntegrity.CompleteObjectModificationsFinder();
        results.forEach(ignored -> objectFinder.recordSuccessfulComparison());
        return PdfContractIntegrity.assess(new PdfContractIntegrity.CompleteDifferencesFinder(), objectFinder, results,
                signatures.stream().map(SignatureWrapper::new).toList());
    }

    private static VerificationService.SignatureResult result(String id, String indication) {
        return new VerificationService.SignatureResult(id, null, null, indication, null, null, false, List.of());
    }

    private static XmlSignature revision(String id) {
        XmlSignature signature = new XmlSignature();
        signature.setId(id);
        XmlByteRange range = new XmlByteRange();
        range.getValue().addAll(List.of(BigInteger.ZERO, BigInteger.TEN,
                BigInteger.valueOf(20), BigInteger.TEN));
        range.setValid(true);
        XmlPDFSignatureDictionary dictionary = new XmlPDFSignatureDictionary();
        dictionary.setSignatureByteRange(range);
        XmlModificationDetection detection = new XmlModificationDetection();
        detection.setObjectModifications(new XmlObjectModifications());
        XmlPDFRevision revision = new XmlPDFRevision();
        revision.setPDFSignatureDictionary(dictionary);
        revision.setModificationDetection(detection);
        signature.setPDFRevision(revision);
        return signature;
    }
}
