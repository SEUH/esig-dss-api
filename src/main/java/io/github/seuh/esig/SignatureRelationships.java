package io.github.seuh.esig;

import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDigestAlgoAndValue;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignatureScope;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

final class SignatureRelationships {
    private SignatureRelationships() {}

    static List<VerificationService.SignatureRelationship> analyze(List<SignatureWrapper> signatures) {
        List<VerificationService.SignatureRelationship> relationships = new ArrayList<>();
        for (int first = 0; first < signatures.size(); first++) {
            for (int second = first + 1; second < signatures.size(); second++) {
                relationships.add(classify(signatures, first, second));
            }
        }
        return relationships;
    }

    private static VerificationService.SignatureRelationship classify(
            List<SignatureWrapper> signatures, int first, int second) {
        SignatureWrapper a = signatures.get(first);
        SignatureWrapper b = signatures.get(second);
        if (countersigns(a, b, signatures)) {
            return relationship("COUNTERSIGNS", a, b, first, second);
        }
        if (countersigns(b, a, signatures)) {
            return relationship("COUNTERSIGNS", b, a, second, first);
        }
        if (a.isCounterSignature() || b.isCounterSignature()) {
            return relationship("UNKNOWN", a, b, first, second);
        }

        PDFRevisionWrapper aRevision = a.getPDFRevision();
        PDFRevisionWrapper bRevision = b.getPDFRevision();
        if (aRevision != null || bRevision != null) {
            if (precedes(aRevision, bRevision)) {
                return relationship("SEQUENTIAL", a, b, first, second);
            }
            if (precedes(bRevision, aRevision)) {
                return relationship("SEQUENTIAL", b, a, second, first);
            }
            return relationship("UNKNOWN", a, b, first, second);
        }

        List<ScopeDigest> aScopes = scopes(a);
        List<ScopeDigest> bScopes = scopes(b);
        if (!aScopes.isEmpty() && aScopes.equals(bScopes)) {
            return relationship("PARALLEL", a, b, first, second);
        }
        return relationship("UNKNOWN", a, b, first, second);
    }

    private static boolean countersigns(SignatureWrapper child, SignatureWrapper parent,
                                        List<SignatureWrapper> signatures) {
        if (!child.isCounterSignature() || child.getParent() == null) {
            return false;
        }
        String parentId = child.getParent().getId();
        if (parentId == null || !parentId.equals(parent.getId())) {
            return false;
        }
        return signatures.stream().filter(signature -> parentId.equals(signature.getId())).count() == 1;
    }

    private static boolean precedes(PDFRevisionWrapper earlier, PDFRevisionWrapper later) {
        if (earlier == null || later == null
                || !earlier.isSignatureByteRangeValid() || !later.isSignatureByteRangeValid()) {
            return false;
        }
        List<BigInteger> earlierRange = earlier.getSignatureByteRange();
        List<BigInteger> laterRange = later.getSignatureByteRange();
        if (!validRange(earlierRange) || !validRange(laterRange)) {
            return false;
        }
        BigInteger earlierEnd = earlierRange.get(2).add(earlierRange.get(3));
        BigInteger laterEnd = laterRange.get(2).add(laterRange.get(3));
        return earlierEnd.compareTo(laterEnd) < 0
                && earlierEnd.compareTo(laterRange.get(1)) <= 0;
    }

    private static boolean validRange(List<BigInteger> range) {
        return range.size() == 4 && BigInteger.ZERO.equals(range.get(0))
                && range.get(1).signum() > 0 && range.get(2).compareTo(range.get(1)) > 0
                && range.get(3).signum() > 0;
    }

    private static List<ScopeDigest> scopes(SignatureWrapper signature) {
        List<XmlSignatureScope> signatureScopes = signature.getSignatureScopes();
        if (signatureScopes == null || signatureScopes.isEmpty()) {
            return List.of();
        }
        List<ScopeDigest> digests = new ArrayList<>();
        for (XmlSignatureScope scope : signatureScopes) {
            if (scope == null || scope.getScope() == null || scope.getSignerData() == null) {
                return List.of();
            }
            XmlDigestAlgoAndValue digest = scope.getSignerData().getDigestAlgoAndValue();
            if (digest == null || digest.getDigestMethod() == null || digest.getDigestValue() == null
                    || !Boolean.TRUE.equals(digest.isMatch())) {
                return List.of();
            }
            digests.add(new ScopeDigest(scope.getScope().name(), digest.getDigestMethod().name(),
                    Base64.getEncoder().encodeToString(digest.getDigestValue())));
        }
        digests.sort(Comparator.comparing(ScopeDigest::scope)
                .thenComparing(ScopeDigest::algorithm).thenComparing(ScopeDigest::value));
        return digests;
    }

    private static VerificationService.SignatureRelationship relationship(
            String type, SignatureWrapper source, SignatureWrapper target, int sourceIndex, int targetIndex) {
        return new VerificationService.SignatureRelationship(
                type, source.getId(), target.getId(), sourceIndex, targetIndex);
    }

    private record ScopeDigest(String scope, String algorithm, String value) {}
}
