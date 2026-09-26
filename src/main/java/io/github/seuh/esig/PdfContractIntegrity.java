package io.github.seuh.esig;

import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlObjectModification;
import eu.europa.esig.dss.enumerations.PdfObjectModificationType;
import eu.europa.esig.dss.pdf.PdfAnnotation;
import eu.europa.esig.dss.pdf.PdfDocumentReader;
import eu.europa.esig.dss.pdf.modifications.CommonPdfModification;
import eu.europa.esig.dss.pdf.modifications.DefaultPdfDifferencesFinder;
import eu.europa.esig.dss.pdf.modifications.DefaultPdfObjectModificationsFinder;
import eu.europa.esig.dss.pdf.modifications.PdfModification;
import eu.europa.esig.dss.pdf.modifications.PdfObjectModifications;
import eu.europa.esig.dss.pdf.modifications.PdfObjectModificationsFinder;
import eu.europa.esig.dss.pdf.visible.ImageUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class PdfContractIntegrity {
    private PdfContractIntegrity() {}

    static VerificationService.ContractIntegrity assess(CompleteDifferencesFinder finder,
            CompleteObjectModificationsFinder objectFinder,
            List<VerificationService.SignatureResult> results, List<SignatureWrapper> signatures) {
        List<VerificationService.IntegrityIssue> issues = new ArrayList<>();
        if (finder == null) {
            issues.add(issue("NON_PDF", null));
            return new VerificationService.ContractIntegrity(false, issues);
        }
        if (results.isEmpty()) {
            issues.add(issue("NO_SIGNATURES", null));
            return new VerificationService.ContractIntegrity(false, issues);
        }

        for (int index = 0; index < results.size(); index++) {
            VerificationService.SignatureResult result = results.get(index);
            String id = result.id();
            if (!"TOTAL_PASSED".equals(result.indication())) {
                issues.add(issue("SIGNATURE_INVALID", id));
            }
            SignatureWrapper signature = index < signatures.size() ? signatures.get(index) : null;
            PDFRevisionWrapper revision = signature == null ? null : signature.getPDFRevision();
            if (revision == null || !revision.isSignatureByteRangeValid()) {
                issues.add(issue("COMPARISON_INCOMPLETE", id));
                continue;
            }
            BigInteger signedEnd = signedEnd(revision);
            if (signedEnd == null) {
                issues.add(issue("COMPARISON_INCOMPLETE", id));
                continue;
            }
            Set<String> laterSignatureFields = new HashSet<>();
            boolean hasLaterSignature = false;
            for (int other = 0; other < signatures.size(); other++) {
                if (other == index || signatures.get(other) == null
                        || signatures.get(other).getPDFRevision() == null) {
                    continue;
                }
                PDFRevisionWrapper otherRevision = signatures.get(other).getPDFRevision();
                BigInteger otherEnd = signedEnd(otherRevision);
                if (otherEnd != null && otherEnd.compareTo(signedEnd) > 0) {
                    hasLaterSignature = true;
                    laterSignatureFields.addAll(otherRevision.getSignatureFieldNames());
                }
            }
            if (!revision.getPdfPageDifferenceConcernedPages().isEmpty()
                    || !revision.getPdfVisualDifferenceConcernedPages().isEmpty()
                    || !revision.getPdfAnnotationChanges().isEmpty()
                    || !revision.getPdfUndefinedChanges().isEmpty()) {
                issues.add(issue("CONTENT_CHANGED", id));
            }
            for (XmlObjectModification change : revision.getPdfSignatureOrFormFillChanges()) {
                if ((change.getFieldName() != null && laterSignatureFields.contains(change.getFieldName()))
                        || (hasLaterSignature && "Sig".equals(change.getType())
                        && change.getAction() == PdfObjectModificationType.CREATION)) {
                    continue;
                }
                issues.add(issue(change.getFieldName() == null ? "COMPARISON_INCOMPLETE" : "CONTENT_CHANGED", id));
                break;
            }
        }
        if (!finder.complete() || objectFinder == null || !objectFinder.completedFor(results.size())) {
            issues.add(issue("COMPARISON_INCOMPLETE", null));
        }
        return new VerificationService.ContractIntegrity(issues.isEmpty(), issues);
    }

    private static VerificationService.IntegrityIssue issue(String code, String signatureId) {
        return new VerificationService.IntegrityIssue(code, signatureId);
    }

    private static BigInteger signedEnd(PDFRevisionWrapper revision) {
        if (!revision.isSignatureByteRangeValid() || revision.getSignatureByteRange().size() != 4) {
            return null;
        }
        return revision.getSignatureByteRange().get(2).add(revision.getSignatureByteRange().get(3));
    }

    /** Tracks successful object comparisons because DSS omits empty comparisons from diagnostic data. */
    static final class CompleteObjectModificationsFinder implements PdfObjectModificationsFinder {
        private final DefaultPdfObjectModificationsFinder delegate = new DefaultPdfObjectModificationsFinder();
        private int successfulComparisons;

        @Override
        public PdfObjectModifications find(PdfDocumentReader signed, PdfDocumentReader finalPdf) {
            PdfObjectModifications result = delegate.find(signed, finalPdf);
            successfulComparisons++;
            return result;
        }

        boolean completedFor(int signatureCount) {
            return successfulComparisons >= signatureCount;
        }

        void recordSuccessfulComparison() {
            successfulComparisons++;
        }
    }

    /** Runs DSS's visual comparison for every page and records failures it would otherwise suppress. */
    static final class CompleteDifferencesFinder extends DefaultPdfDifferencesFinder {
        private boolean complete = true;

        boolean complete() {
            return complete;
        }

        @Override
        public List<PdfModification> getAnnotationOverlaps(PdfDocumentReader reader) {
            List<PdfModification> overlaps = new ArrayList<>();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                try {
                    List<PdfAnnotation> annotations = new ArrayList<>(reader.getPdfAnnotations(page));
                    Iterator<PdfAnnotation> iterator = annotations.iterator();
                    while (iterator.hasNext()) {
                        PdfAnnotation annotation = iterator.next();
                        iterator.remove();
                        if (isAnnotationBoxOverlapping(annotation.getAnnotationBox(), annotations)) {
                            overlaps.add(new CommonPdfModification(page));
                            break;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    complete = false;
                }
            }
            return overlaps;
        }

        @Override
        public List<PdfModification> getVisualDifferences(PdfDocumentReader signed, PdfDocumentReader finalPdf) {
            List<PdfModification> differences = new ArrayList<>();
            int pages = Math.min(signed.getNumberOfPages(), finalPdf.getNumberOfPages());
            if (pages == 0) {
                complete = false;
            }
            for (int page = 1; page <= pages; page++) {
                try {
                    BufferedImage signedImage = signed.generateImageScreenshot(page);
                    List<PdfAnnotation> signedAnnotations = signed.getPdfAnnotations(page);
                    List<PdfAnnotation> finalAnnotations = finalPdf.getPdfAnnotations(page);
                    List<PdfAnnotation> addedAnnotations = new ArrayList<>();
                    for (PdfAnnotation annotation : finalAnnotations) {
                        if (!signedAnnotations.contains(annotation)) {
                            addedAnnotations.add(annotation);
                        }
                    }
                    BufferedImage finalImage = finalPdf.generateImageScreenshotWithoutAnnotations(
                            page, addedAnnotations);
                    if (!ImageUtils.imagesEqual(signedImage, finalImage)) {
                        differences.add(new CommonPdfModification(page));
                    }
                } catch (IOException | RuntimeException e) {
                    complete = false;
                }
            }
            return differences;
        }
    }
}
