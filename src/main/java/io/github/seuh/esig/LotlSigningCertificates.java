package io.github.seuh.esig;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.x509.CommonCertificateSource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

final class LotlSigningCertificates {
    // Official Journal C/2026/1944, Annex (15 April 2026).
    // These authenticate the EU LOTL; they are not QES trust anchors themselves.
    private static final Set<String> OFFICIAL_SHA256 = Set.of(
            "c0641c4f7d56c431b1c924742db7fce9c1eef7d7fd212113a2768486b3abcdc5",
            "e0a620fbb6747362bb933ac44169d676a553444716cf5f31605f12a22b8396b1",
            "df7e29360c34b2b8d6d5f40325c1d4d12c9922cecd33b7407674a74b2b3ca1e5",
            "b63d416744e7098bf9ec2caa596a93bc2468e37f8284ba65ecc061711bcbaa18",
            "236103f03a8031ae8f47f9059bf8de38564cdbfebedde4a597d50f8980aa653b",
            "d2064fdd70f6982dcc516b86d9d5c56aea939417c624b2e478c0b29de54f8474"
    );

    private LotlSigningCertificates() {}

    static CommonCertificateSource load() throws IOException, CertificateException, NoSuchAlgorithmException {
        try (InputStream input = LotlSigningCertificates.class.getResourceAsStream("/eu-lotl-signers.pem")) {
            if (input == null) {
                throw new IOException("Bundled Official Journal LOTL certificates are missing");
            }
            CommonCertificateSource source = new CommonCertificateSource();
            Set<String> actual = new HashSet<>();
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (var certificate : factory.generateCertificates(input)) {
                X509Certificate x509 = (X509Certificate) certificate;
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));
                if (!OFFICIAL_SHA256.contains(digest)) {
                    throw new CertificateException("Bundled LOTL certificate does not match the Official Journal");
                }
                actual.add(digest);
                source.addCertificate(new CertificateToken(x509));
            }
            if (!actual.equals(OFFICIAL_SHA256)) {
                throw new CertificateException("Bundled LOTL certificates are incomplete");
            }
            return source;
        }
    }
}
