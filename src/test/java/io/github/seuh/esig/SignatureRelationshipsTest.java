package io.github.seuh.esig;

import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlByteRange;
import eu.europa.esig.dss.diagnostic.jaxb.XmlDigestAlgoAndValue;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFRevision;
import eu.europa.esig.dss.diagnostic.jaxb.XmlPDFSignatureDictionary;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignature;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignatureScope;
import eu.europa.esig.dss.diagnostic.jaxb.XmlSignerData;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureScopeType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureRelationshipsTest {
    @Test
    void emptyAndSingleSignatureHaveNoRelationships() {
        assertEquals(List.of(), SignatureRelationships.analyze(List.of()));
        assertEquals(List.of(), SignatureRelationships.analyze(List.of(new SignatureWrapper(signature("one")))));
    }

    @Test
    void matchingSignedDataIsParallel() {
        var relationships = SignatureRelationships.analyze(List.of(
                new SignatureWrapper(signedData(signature("one"), 7)),
                new SignatureWrapper(signedData(signature("two"), 7))));
        assertEquals(List.of(new VerificationService.SignatureRelationship(
                "PARALLEL", "one", "two", 0, 1)), relationships);
    }

    @Test
    void pdfByteRangesEstablishSigningOrderEvenWhenReportOrderIsReversed() {
        var relationships = SignatureRelationships.analyze(List.of(
                pdf(signature("later"), 0, 180, 220, 80),
                pdf(signature("earlier"), 0, 40, 80, 40)));
        assertEquals(List.of(new VerificationService.SignatureRelationship(
                "SEQUENTIAL", "earlier", "later", 1, 0)), relationships);
    }

    @Test
    void counterSignaturePointsToItsImmediateParent() {
        XmlSignature parent = signature("parent");
        XmlSignature child = signature("child");
        child.setCounterSignature(true);
        child.setParent(parent);
        var relationships = SignatureRelationships.analyze(List.of(
                new SignatureWrapper(child), new SignatureWrapper(parent)));
        assertEquals(List.of(new VerificationService.SignatureRelationship(
                "COUNTERSIGNS", "child", "parent", 0, 1)), relationships);
    }

    @Test
    void mixedAndUnprovablePairsAreReportedSeparately() {
        XmlSignature parent = signedData(signature("parent"), 7);
        XmlSignature peer = signedData(signature("peer"), 7);
        XmlSignature unrelated = signedData(signature("unrelated"), 9);
        XmlSignature child = signature("child");
        child.setCounterSignature(true);
        child.setParent(parent);
        var relationships = SignatureRelationships.analyze(List.of(
                new SignatureWrapper(parent), new SignatureWrapper(peer),
                new SignatureWrapper(unrelated), new SignatureWrapper(child)));
        assertEquals(6, relationships.size());
        assertEquals(new VerificationService.SignatureRelationship(
                "PARALLEL", "parent", "peer", 0, 1), relationships.get(0));
        assertEquals(new VerificationService.SignatureRelationship(
                "UNKNOWN", "parent", "unrelated", 0, 2), relationships.get(1));
        assertEquals(new VerificationService.SignatureRelationship(
                "COUNTERSIGNS", "child", "parent", 3, 0), relationships.get(2));
    }

    private static XmlSignature signature(String id) {
        XmlSignature signature = new XmlSignature();
        signature.setId(id);
        return signature;
    }

    private static XmlSignature signedData(XmlSignature signature, int digestByte) {
        XmlDigestAlgoAndValue digest = new XmlDigestAlgoAndValue();
        digest.setDigestMethod(DigestAlgorithm.SHA256);
        digest.setDigestValue(new byte[] {(byte) digestByte});
        digest.setMatch(true);
        XmlSignerData data = new XmlSignerData();
        data.setDigestAlgoAndValue(digest);
        XmlSignatureScope scope = new XmlSignatureScope();
        scope.setScope(SignatureScopeType.FULL);
        scope.setSignerData(data);
        signature.getSignatureScopes().add(scope);
        return signature;
    }

    private static SignatureWrapper pdf(XmlSignature signature, long... byteRange) {
        XmlByteRange range = new XmlByteRange();
        for (long value : byteRange) {
            range.getValue().add(BigInteger.valueOf(value));
        }
        range.setValid(true);
        XmlPDFSignatureDictionary dictionary = new XmlPDFSignatureDictionary();
        dictionary.setSignatureByteRange(range);
        XmlPDFRevision revision = new XmlPDFRevision();
        revision.setPDFSignatureDictionary(dictionary);
        signature.setPDFRevision(revision);
        return new SignatureWrapper(signature);
    }
}
