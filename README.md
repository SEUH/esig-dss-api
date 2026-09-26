# EU QES verification API

Java 25, Maven, Spring Boot 4.1.1, and European Commission DSS 6.5. This service exposes one endpoint, `POST /api/verify`, for validating signed documents and listing every signature and its certificate chain. Supported DSS formats include PAdES (PDF), CAdES, XAdES, and ASiC containers. Detached signatures need their signed content and are outside this single-document endpoint.

> **Security notice:** This is a basic implementation and has not undergone a security audit. Use it at your own risk. Review it for your threat model and obtain an independent security review before relying on it in production.

## EU trust setup

QES classification requires the European List of Trusted Lists (LOTL), its national trusted lists, and revocation information. DSS provides the LOTL validation machinery but does not bundle the EU LOTL signing certificates as trust anchors. This app includes the six LOTL-signing certificates from [Official Journal C/2026/1944](https://eur-lex.europa.eu/eli/C/2026/1944/oj/eng/pdf) and checks each against its published SHA-256 digest at startup. Those certificates authenticate the LOTL; DSS then downloads the LOTL and linked national trusted lists and validates their signatures and validity periods. The app marks trust data ready only when DSS reports the LOTL and every linked national list as valid and certificates have been loaded. Otherwise it keeps serving requests but returns HTTP 503 for verification until a refresh succeeds. The national lists supply trusted service certificates. There is no single EU root CA for QES.

```bash
export ESIG_LOTL_CACHE_DIR=/var/cache/esig-dss-api
mvn package
java -jar target/esig-dss-api-0.1.0.jar
```

The app loads and checks the EU LOTL and national lists at startup, then refreshes them every six hours. Set `ESIG_LOTL_REFRESH_INTERVAL` to a Spring duration such as `PT12H` to change that interval. It rejects expired or invalidly signed lists and returns HTTP 503 until every linked list has validated. At verification time DSS fetches OCSP or CRL revocation data for the certificate chain, caches it until its `NextUpdate`, and fetches it again when stale. The app also refreshes revocation data hourly for up to 128 recently verified certificates; set `ESIG_REVOCATION_REFRESH_INTERVAL` to change that interval. It retrieves missing issuer certificates through AIA. Network access to the lists and certificate-specific OCSP, CRL, and AIA endpoints is needed for complete results. An exceptional Official Journal replacement of the LOTL signers requires updating the bundled certificates and their published digests.

## Endpoint

Multipart, raw `application/octet-stream`, and JSON object requests use the same path. The maximum document size is 25 MiB. Raw requests may set `X-Filename`; if omitted, the service uses `document`.

```bash
curl -F 'file=@signed.pdf' http://localhost:8080/api/verify
curl -H 'Content-Type: application/octet-stream' -H 'X-Filename: signed.pdf' \
  --data-binary @signed.pdf http://localhost:8080/api/verify
curl -H 'Content-Type: application/json' \
  -d '{"filename":"signed.pdf","base64":"JVBERi0..."}' \
  http://localhost:8080/api/verify
```

The JSON response has `signatureCount` and `signatures`. Each signature includes DSS `indication`, `subIndication`, `qualification`, signer name, claimed signing time, `qesValid`, and `certificateChain`. Each certificate has subject, issuer, serial number, validity dates, and its complete DER encoding as Base64. `qesValid` is true only when DSS reports both `TOTAL_PASSED` and `QESIG`. A reported signing time is a claim from the signature and should not be treated as independently proven unless supported by valid time evidence.

The response also has `signatureRelationships`, with one entry for each pair of signatures. Each entry contains `type`, `sourceId`, `targetId`, and zero-based `sourceIndex` and `targetIndex` into `signatures`. `COUNTERSIGNS` points from a countersignature to its parent. `SEQUENTIAL` points from an earlier PDF signature to a later one whose signed revision covers it. `PARALLEL` means independent signatures have matching verified signed-data scopes. `UNKNOWN` means the available evidence does not establish the relationship. For `PARALLEL` and `UNKNOWN`, source and target follow their order in `signatures`. A document with fewer than two signatures has an empty `signatureRelationships` list. Signature validity is reported separately for each signature.

`contractIntegrity` is a document-level check for signed PDFs. Its `passed` value is true only when every signature has DSS `TOTAL_PASSED` and no contract content change is detected between any signed revision and the submitted PDF. The `issues` list identifies `SIGNATURE_INVALID`, `CONTENT_CHANGED`, or `COMPARISON_INCOMPLETE`, with a signature ID when applicable. PDFs without signatures and other document formats return `passed: false` with `NO_SIGNATURES` or `NON_PDF`. Identifiable later signature fields and validation data are allowed; changes to form values, annotations, pages, or other content fail the check. Visual comparison runs across every page, so large PDFs can take longer to verify. This check does not establish whether content changed before the first signature, and PDF visual comparison cannot guarantee detection of every malicious change.

Invalid or unsupported documents return HTTP 422. Missing trust data returns HTTP 503. A supported document with no signatures returns an empty signature list.

Malformed JSON or multipart requests return a short HTTP 400 error without echoing the request body. The default logs show trust-list refresh outcomes, revocation refresh summaries, and verification counts and duration. Enable `DEBUG` for `io.github.seuh.esig` when investigating validation details; submitted documents, filenames, and certificate bytes are not included in application log messages.

## Container

Build and run the image locally, persisting DSS cache files in a named volume. The current LOTL refresh setting still fetches the lists at startup, even when cache files exist.

```bash
docker build -t esig-dss-api .
docker run --rm -p 8080:8080 \
  -v esig-dss-cache:/var/cache/esig-dss-api \
  esig-dss-api
```

To run a published GHCR release with Compose, set `ESIG_DSS_TAG` to a published version (or leave it as `latest`) and optionally change the host port with `ESIG_DSS_PORT`:

```bash
ESIG_DSS_TAG=0.1.0 ESIG_DSS_PORT=8080 docker compose up -d
```

If the GHCR package is private, authenticate first with `docker login ghcr.io`.

Pushing a `v*` Git tag runs the Maven test suite and then publishes the image to `ghcr.io/seuh/esig-dss-api` with the release tag, semantic version tags, and `latest`. The workflow uses the repository's `GITHUB_TOKEN`; no registry secret is needed. GitHub Container Registry packages are private by default, so change the package visibility in its GitHub settings if public pulls are intended.

References: [DSS validation and trusted-list configuration](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html), [eIDAS trusted-list dashboard](https://eidas.ec.europa.eu/efda/trust-services/browse/eidas/tls), [eIDAS API documentation](https://eidas.ec.europa.eu/efda/swagger-ui/index.html), [DSS releases](https://ec.europa.eu/digital-building-blocks/sites/spaces/DIGITAL/pages/467109114/DSS+releases), [Spring Boot releases](https://docs.spring.io/spring-boot/).

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
