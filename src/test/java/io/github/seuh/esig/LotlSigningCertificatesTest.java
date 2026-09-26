package io.github.seuh.esig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LotlSigningCertificatesTest {
    @Test
    void bundledCertificatesMatchOfficialJournal() throws Exception {
        assertEquals(6, LotlSigningCertificates.load().getCertificates().size());
    }
}
