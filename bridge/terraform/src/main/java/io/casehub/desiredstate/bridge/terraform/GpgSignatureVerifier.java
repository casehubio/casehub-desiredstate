package io.casehub.desiredstate.bridge.terraform;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

public final class GpgSignatureVerifier {

    private GpgSignatureVerifier() {}

    public sealed interface VerificationResult {
        record Verified(String keyId) implements VerificationResult {}
        record Failed(String reason) implements VerificationResult {}
    }

    public static VerificationResult verify(
            byte[] sha256sumsContent,
            byte[] signatureBytes,
            List<RegistryPackageResponse.GpgPublicKey> publicKeys,
            Set<String> trustedKeyIds) {
        try {
            PGPSignature signature = parseSignature(signatureBytes);
            long sigKeyId = signature.getKeyID();
            String sigKeyIdHex = String.format("%016X", sigKeyId);

            if (!trustedKeyIds.isEmpty() && !trustedKeyIds.contains(sigKeyIdHex)) {
                return new VerificationResult.Failed(
                    "Signature key ID " + sigKeyIdHex + " not in trusted set: " + trustedKeyIds);
            }

            PGPPublicKey matchingKey = findMatchingKey(publicKeys, sigKeyId);
            if (matchingKey == null) {
                return new VerificationResult.Failed(
                    "No public key found for signature key ID " + sigKeyIdHex);
            }

            signature.init(new JcaPGPContentVerifierBuilderProvider(), matchingKey);
            signature.update(sha256sumsContent);

            if (signature.verify()) {
                return new VerificationResult.Verified(sigKeyIdHex);
            }
            return new VerificationResult.Failed("GPG signature verification failed");
        } catch (Exception e) {
            return new VerificationResult.Failed("GPG verification error: " + e.getMessage());
        }
    }

    private static PGPSignature parseSignature(byte[] sigBytes) throws Exception {
        JcaPGPObjectFactory factory = new JcaPGPObjectFactory(sigBytes);
        Object obj = factory.nextObject();
        if (obj instanceof PGPSignatureList sigList && !sigList.isEmpty()) {
            return sigList.get(0);
        }
        throw new PGPException("No signature found in signature data");
    }

    private static PGPPublicKey findMatchingKey(
            List<RegistryPackageResponse.GpgPublicKey> keys, long keyId) throws Exception {
        for (var gpgKey : keys) {
            try (InputStream is = PGPUtil.getDecoderStream(
                    new ByteArrayInputStream(gpgKey.asciiArmor().getBytes()))) {
                PGPPublicKeyRingCollection keyRings = new PGPPublicKeyRingCollection(
                    is, new JcaKeyFingerprintCalculator());
                PGPPublicKey key = keyRings.getPublicKey(keyId);
                if (key != null) {
                    return key;
                }
            }
        }
        return null;
    }
}
