package io.github.seuh.esig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CmsImplementationTest {
    @Test
    void cmsImplementationIsAvailable() {
        assertDoesNotThrow(() -> Class.forName("eu.europa.esig.dss.cms.CMSUtils"));
    }
}
