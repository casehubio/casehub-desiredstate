package io.casehub.desiredstate.bridge.terraform;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public record CertificateBundle(
    String certPem,
    String keyPem,
    X509Certificate certificate,
    PrivateKey privateKey
) {}
