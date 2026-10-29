package io.casehub.desiredstate.bridge.terraform;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public final class PluginCertificateGenerator {

    private static final String HOST = "localhost";
    private static final Duration NOT_BEFORE_SKEW = Duration.ofSeconds(30);
    private static final Duration VALIDITY = Duration.ofHours(262_980);

    private PluginCertificateGenerator() {}

    public static CertificateBundle generate() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec("secp521r1"), new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();

            Instant now = Instant.now();
            Date notBefore = Date.from(now.minus(NOT_BEFORE_SKEW));
            Date notAfter = Date.from(now.plus(VALIDITY));

            BigInteger serial = new BigInteger(128, new SecureRandom());

            X500Name subject = new X500Name("CN=" + HOST + ",O=CaseHub");

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

            builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment
                    | KeyUsage.keyAgreement | KeyUsage.keyCertSign));
            builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(new KeyPurposeId[]{
                    KeyPurposeId.id_kp_serverAuth,
                    KeyPurposeId.id_kp_clientAuth
                }));
            builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, HOST)));

            ContentSigner signer = new JcaContentSignerBuilder("SHA512withECDSA")
                .build(keyPair.getPrivate());

            X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);

            String certPem = toPem(cert);
            String keyPem = toPem(keyPair.getPrivate());

            return new CertificateBundle(certPem, keyPem, cert, keyPair.getPrivate());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate mTLS certificate", e);
        }
    }

    private static String toPem(Object obj) {
        try (StringWriter sw = new StringWriter();
             JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(obj);
            pw.flush();
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("PEM encoding failed", e);
        }
    }
}
