package io.casehub.desiredstate.bridge.terraform;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GpgSignatureVerifierTest {

    private static String asciiArmoredPublicKey;
    private static PGPSecretKey secretKey;
    private static String keyId;

    @BeforeAll
    static void generateTestKey() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, new Date());

        PGPSignatureSubpacketGenerator subpackets = new PGPSignatureSubpacketGenerator();
        subpackets.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER);
        subpackets.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);
        subpackets.setPreferredHashAlgorithms(false,
            new int[]{HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA512});

        PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION, pgpKeyPair,
            "test@example.com",
            new JcaPGPDigestCalculatorProviderBuilder().build()
                .get(HashAlgorithmTags.SHA256),
            subpackets.generate(), null,
            new JcaPGPContentSignerBuilder(
                pgpKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
            new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.NULL)
                .build(new char[0])
        );

        PGPPublicKeyRing publicKeyRing = keyRingGen.generatePublicKeyRing();
        PGPSecretKeyRing secretKeyRing = keyRingGen.generateSecretKeyRing();
        secretKey = secretKeyRing.getSecretKey();

        keyId = String.format("%016X", publicKeyRing.getPublicKey().getKeyID());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ArmoredOutputStream aos = new ArmoredOutputStream(bos)) {
            publicKeyRing.encode(aos);
        }
        asciiArmoredPublicKey = bos.toString();
    }

    private byte[] sign(byte[] content) throws Exception {
        PGPPrivateKey privateKey = secretKey.extractPrivateKey(
            new JcePBESecretKeyDecryptorBuilder().build(new char[0]));

        PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
            new JcaPGPContentSignerBuilder(
                secretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256));
        sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
        sigGen.update(content);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var out = new org.bouncycastle.bcpg.BCPGOutputStream(bos)) {
            sigGen.generate().encode(out);
        }
        return bos.toByteArray();
    }

    @Test
    void verifyValidSignature() throws Exception {
        byte[] content = "abc123  provider.zip\n".getBytes();
        byte[] sig = sign(content);

        var key = new RegistryPackageResponse.GpgPublicKey(
            keyId, asciiArmoredPublicKey, "Test", null);

        var result = GpgSignatureVerifier.verify(content, sig, List.of(key), Set.of());
        assertThat(result).isInstanceOf(GpgSignatureVerifier.VerificationResult.Verified.class);
        assertThat(((GpgSignatureVerifier.VerificationResult.Verified) result).keyId())
            .isEqualTo(keyId);
    }

    @Test
    void rejectTamperedContent() throws Exception {
        byte[] content = "abc123  provider.zip\n".getBytes();
        byte[] sig = sign(content);

        byte[] tampered = "def456  provider.zip\n".getBytes();

        var key = new RegistryPackageResponse.GpgPublicKey(
            keyId, asciiArmoredPublicKey, "Test", null);

        var result = GpgSignatureVerifier.verify(tampered, sig, List.of(key), Set.of());
        assertThat(result).isInstanceOf(GpgSignatureVerifier.VerificationResult.Failed.class);
    }

    @Test
    void rejectUntrustedKeyId() throws Exception {
        byte[] content = "abc123  provider.zip\n".getBytes();
        byte[] sig = sign(content);

        var key = new RegistryPackageResponse.GpgPublicKey(
            keyId, asciiArmoredPublicKey, "Test", null);

        var result = GpgSignatureVerifier.verify(content, sig, List.of(key),
            Set.of("AAAA1111BBBB2222"));
        assertThat(result).isInstanceOf(GpgSignatureVerifier.VerificationResult.Failed.class);
        assertThat(((GpgSignatureVerifier.VerificationResult.Failed) result).reason())
            .contains("not in trusted set");
    }

    @Test
    void acceptTrustedKeyId() throws Exception {
        byte[] content = "abc123  provider.zip\n".getBytes();
        byte[] sig = sign(content);

        var key = new RegistryPackageResponse.GpgPublicKey(
            keyId, asciiArmoredPublicKey, "Test", null);

        var result = GpgSignatureVerifier.verify(content, sig, List.of(key), Set.of(keyId));
        assertThat(result).isInstanceOf(GpgSignatureVerifier.VerificationResult.Verified.class);
    }

    @Test
    void acceptAnyKeyWhenTrustedSetEmpty() throws Exception {
        byte[] content = "abc123  provider.zip\n".getBytes();
        byte[] sig = sign(content);

        var key = new RegistryPackageResponse.GpgPublicKey(
            keyId, asciiArmoredPublicKey, "Test", null);

        var result = GpgSignatureVerifier.verify(content, sig, List.of(key), Set.of());
        assertThat(result).isInstanceOf(GpgSignatureVerifier.VerificationResult.Verified.class);
    }
}
