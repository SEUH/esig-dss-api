package io.github.seuh.esig;

import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.model.job.DocumentInfo;
import eu.europa.esig.dss.model.tsl.TLInfo;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.crl.FileCacheCRLSource;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.service.ocsp.FileCacheOCSPSource;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import eu.europa.esig.dss.tsl.sync.ExpirationAndSignatureCheckStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class TrustLists {
    private static final Logger log = LoggerFactory.getLogger(TrustLists.class);
    private static final int MAX_TRACKED_CERTIFICATES = 128;
    private final String url;
    private final String cacheDir;
    private final ConcurrentHashMap<String, RevocationPair> trackedCertificates = new ConcurrentHashMap<>();
    private volatile TrustedListsCertificateSource trusted;
    private volatile boolean ready;

    public TrustLists(@Value("${esig.lotl.url}") String url,
                      @Value("${esig.lotl.cache-dir}") String cacheDir) {
        this.url = url;
        this.cacheDir = cacheDir;
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${esig.lotl.refresh-interval:PT6H}",
               initialDelayString = "${esig.lotl.refresh-interval:PT6H}")
    public synchronized void refresh() {
        long started = System.nanoTime();
        log.debug("EU trust-list refresh started");
        ready = false;
        try {
            File directory = new File(cacheDir);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create trusted-list cache directory");
            }
            LOTLSource lotl = new LOTLSource();
            lotl.setUrl(url);
            lotl.setCertificateSource(LotlSigningCertificates.load());
            lotl.setPivotSupport(true);
            lotl.setTLVersions(java.util.Arrays.asList(5, 6));

            CommonsDataLoader online = new CommonsDataLoader();
            online.setTimeoutConnection(10_000);
            online.setTimeoutResponse(20_000);
            FileCacheDataLoader loader = new FileCacheDataLoader(online);
            loader.setFileCacheDirectory(directory);
            loader.setCacheExpirationTime(0);
            TLValidationJob job = new TLValidationJob();
            job.setOnlineDataLoader(loader);
            TrustedListsCertificateSource refreshed = new TrustedListsCertificateSource();
            job.setTrustedListCertificateSource(refreshed);
            job.setListOfTrustedListSources(lotl);
            job.setSynchronizationStrategy(new ExpirationAndSignatureCheckStrategy());
            job.onlineRefresh();
            var summary = job.getSummary();
            if (summary == null) {
                throw new IllegalStateException("EU trusted-list validation returned no summary");
            }
            List<TLInfo> nationalLists = summary.getLOTLInfos().stream()
                    .flatMap(info -> info.getTLInfos().stream()).toList();
            summary.getLOTLInfos().stream().filter(info -> !valid(info)).forEach(TrustLists::logInvalid);
            nationalLists.stream().filter(info -> !valid(info)).forEach(TrustLists::logInvalid);
            if (summary.getLOTLInfos().isEmpty() || nationalLists.isEmpty()
                    || summary.getLOTLInfos().stream().anyMatch(info -> !valid(info))
                    || nationalLists.stream().anyMatch(info -> !valid(info))) {
                throw new IllegalStateException("EU LOTL or a national trusted list failed validation");
            }
            if (refreshed.getCertificates().isEmpty()) {
                throw new IllegalStateException("No certificates were loaded from valid EU trusted lists");
            }
            trusted = refreshed;
            ready = true;
            log.info("EU trust data ready: nationalLists={}, trustedCertificates={}, durationMs={}",
                    nationalLists.size(), refreshed.getCertificates().size(), elapsedMillis(started));
        } catch (Exception e) {
            log.error("EU trust-list refresh failed after {} ms; verification unavailable: {}",
                    elapsedMillis(started), e.toString());
            log.debug("EU trust-list refresh failure details", e);
        }
    }

    public CommonCertificateVerifier verifier() {
        TrustedListsCertificateSource current = trusted;
        if (!ready || current == null) {
            throw new TrustUnavailableException();
        }
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        verifier.setTrustedCertSources(current);
        verifier.setCrlSource(crlSource());
        verifier.setOcspSource(ocspSource());
        verifier.setAIASource(new DefaultAIASource());
        return verifier;
    }

    void rememberChain(List<CertificateWrapper> chain) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<CertificateToken> certificates = new ArrayList<>();
            for (CertificateWrapper wrapper : chain) {
                byte[] der = wrapper.getBinaries();
                if (der != null) {
                    certificates.add(new CertificateToken((X509Certificate)
                            factory.generateCertificate(new ByteArrayInputStream(der))));
                }
            }
            for (CertificateToken child : certificates) {
                for (CertificateToken issuer : certificates) {
                    if (child == issuer || !child.getIssuerX500Principal().equals(issuer.getCertificate().getSubjectX500Principal())) {
                        continue;
                    }
                    try {
                        child.getCertificate().verify(issuer.getPublicKey());
                        String id = child.getDSSIdAsString();
                        if (trackedCertificates.containsKey(id) || trackedCertificates.size() < MAX_TRACKED_CERTIFICATES) {
                            trackedCertificates.put(id, new RevocationPair(child, issuer, Instant.now()));
                        }
                        break;
                    } catch (GeneralSecurityException ignored) {
                        // A matching subject name alone does not establish the issuer.
                    }
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            log.debug("Could not track certificate chain for revocation refresh: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${esig.revocation.refresh-interval:PT1H}",
               initialDelayString = "${esig.revocation.refresh-interval:PT1H}")
    public void refreshRevocations() {
        if (!ready || trackedCertificates.isEmpty()) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        FileCacheOCSPSource ocsp = ocspSource();
        FileCacheCRLSource crl = crlSource();
        long started = System.nanoTime();
        int attempted = 0;
        int refreshed = 0;
        int unavailable = 0;
        int failed = 0;
        for (var entry : trackedCertificates.entrySet()) {
            RevocationPair pair = entry.getValue();
            if (pair.lastSeen().isBefore(cutoff)) {
                trackedCertificates.remove(entry.getKey(), pair);
                continue;
            }
            attempted++;
            try {
                if (ocsp.getRevocationToken(pair.certificate(), pair.issuer(), true) != null
                        || crl.getRevocationToken(pair.certificate(), pair.issuer(), true) != null) {
                    refreshed++;
                } else {
                    unavailable++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.debug("Revocation refresh failed for a tracked certificate: {}", e.getClass().getSimpleName());
            }
        }
        if (unavailable > 0 || failed > 0) {
            log.warn("Revocation refresh incomplete: attempted={}, refreshed={}, unavailable={}, failed={}, durationMs={}",
                    attempted, refreshed, unavailable, failed, elapsedMillis(started));
        } else {
            log.info("Revocation data refreshed: certificates={}, durationMs={}", refreshed, elapsedMillis(started));
        }
    }

    private FileCacheCRLSource crlSource() {
        FileCacheCRLSource source = new FileCacheCRLSource(new OnlineCRLSource());
        source.setFileCacheDirectory(new File(cacheDir, "crl"));
        return source;
    }

    private FileCacheOCSPSource ocspSource() {
        FileCacheOCSPSource source = new FileCacheOCSPSource(new OnlineOCSPSource());
        source.setFileCacheDirectory(new File(cacheDir, "ocsp"));
        return source;
    }

    private static boolean valid(DocumentInfo info) {
        return info.getValidationCacheInfo() != null && info.getValidationCacheInfo().isValid();
    }

    private static void logInvalid(DocumentInfo info) {
        var validation = info.getValidationCacheInfo();
        log.warn("Trusted list {} failed validation: {} / {}", info.getUrl(),
                validation == null ? "not validated" : validation.getIndication(),
                validation == null ? "" : validation.getSubIndication());
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record RevocationPair(CertificateToken certificate, CertificateToken issuer, Instant lastSeen) {}
}
