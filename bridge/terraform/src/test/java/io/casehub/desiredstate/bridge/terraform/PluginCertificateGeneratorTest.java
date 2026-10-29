package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.spec.ECParameterSpec;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCertificateGeneratorTest {

    @Test
    void generatedCertUsesEcdsaP521() {
        var bundle = PluginCertificateGenerator.generate();
        X509Certificate cert = bundle.certificate();

        assertThat(cert.getSigAlgName()).containsIgnoringCase("ECDSA");
        assertThat(cert.getPublicKey()).isInstanceOf(ECKey.class);

        ECParameterSpec params = ((ECKey) cert.getPublicKey()).getParams();
        assertThat(params.getOrder().bitLength()).isGreaterThanOrEqualTo(519);
    }

    @Test
    void generatedCertHasLocalhostSubject() {
        var bundle = PluginCertificateGenerator.generate();
        X509Certificate cert = bundle.certificate();

        assertThat(cert.getSubjectX500Principal().getName()).contains("CN=localhost");
    }

    @Test
    void generatedCertHasLocalhostSan() throws Exception {
        var bundle = PluginCertificateGenerator.generate();
        X509Certificate cert = bundle.certificate();

        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        assertThat(sans).isNotNull();
        boolean hasLocalhostDns = sans.stream()
            .anyMatch(san -> (Integer) san.get(0) == 2
                && "localhost".equals(san.get(1)));
        assertThat(hasLocalhostDns).isTrue();
    }

    @Test
    void generatedCertIsCa() {
        var bundle = PluginCertificateGenerator.generate();
        X509Certificate cert = bundle.certificate();

        assertThat(cert.getBasicConstraints()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void generatedCertHasCorrectKeyUsage() {
        var bundle = PluginCertificateGenerator.generate();
        X509Certificate cert = bundle.certificate();

        boolean[] keyUsage = cert.getKeyUsage();
        assertThat(keyUsage).isNotNull();
        assertThat(keyUsage[0]).isTrue();  // digitalSignature
        assertThat(keyUsage[2]).isTrue();  // keyEncipherment
        assertThat(keyUsage[4]).isTrue();  // keyAgreement
        assertThat(keyUsage[5]).isTrue();  // keyCertSign
    }

    @Test
    void generatedCertHasClientAndServerAuth() throws Exception {
        var bundle = PluginCertificateGenerator.generate();
        X509Certificate cert = bundle.certificate();

        List<String> extKeyUsage = cert.getExtendedKeyUsage();
        assertThat(extKeyUsage).contains(
            "1.3.6.1.5.5.7.3.1",  // serverAuth
            "1.3.6.1.5.5.7.3.2"   // clientAuth
        );
    }

    @Test
    void certPemIsValidPem() {
        var bundle = PluginCertificateGenerator.generate();

        assertThat(bundle.certPem()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(bundle.certPem()).contains("-----END CERTIFICATE-----");
    }

    @Test
    void keyPemIsValidPem() {
        var bundle = PluginCertificateGenerator.generate();

        assertThat(bundle.keyPem()).startsWith("-----BEGIN EC PRIVATE KEY-----");
        assertThat(bundle.keyPem()).contains("-----END EC PRIVATE KEY-----");
    }

    @Test
    void twoCertificatesHaveDifferentKeys() {
        var bundle1 = PluginCertificateGenerator.generate();
        var bundle2 = PluginCertificateGenerator.generate();

        assertThat(bundle1.certificate().getSerialNumber())
            .isNotEqualTo(bundle2.certificate().getSerialNumber());
        assertThat(bundle1.privateKey()).isNotEqualTo(bundle2.privateKey());
    }

    @Test
    void privateKeyMatchesCertificatePublicKey() {
        var bundle = PluginCertificateGenerator.generate();

        ECParameterSpec certParams = ((ECKey) bundle.certificate().getPublicKey()).getParams();
        ECParameterSpec keyParams = ((ECKey) bundle.privateKey()).getParams();
        assertThat(certParams.getCurve()).isEqualTo(keyParams.getCurve());
    }
}
