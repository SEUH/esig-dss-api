package io.github.seuh.esig;

public class TrustUnavailableException extends RuntimeException {
    public TrustUnavailableException() {
        super("EU trusted lists are unavailable; QES status cannot be determined");
    }
}
