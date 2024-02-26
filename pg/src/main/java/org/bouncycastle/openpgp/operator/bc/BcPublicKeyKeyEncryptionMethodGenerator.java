package org.bouncycastle.openpgp.operator.bc;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;

import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.bcpg.ECDHPublicBCPGKey;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.AsymmetricCipherKeyPairGenerator;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.KeyGenerationParameters;
import org.bouncycastle.crypto.RawAgreement;
import org.bouncycastle.crypto.Wrapper;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.agreement.X448Agreement;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.X448KeyPairGenerator;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X448KeyGenerationParameters;
import org.bouncycastle.crypto.params.X448PublicKeyParameters;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.operator.PGPPad;
import org.bouncycastle.openpgp.operator.PublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.openpgp.operator.RFC6637Utils;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.BigIntegers;

/**
 * A method generator for supporting public key based encryption operations.
 */
public class BcPublicKeyKeyEncryptionMethodGenerator
    extends PublicKeyKeyEncryptionMethodGenerator
{
    private static final byte X_HDR = 0x40;

    private SecureRandom random;
    private BcPGPKeyConverter keyConverter = new BcPGPKeyConverter();

    /**
     * Create a public key encryption method generator with the method to be based on the passed in key.
     *
     * @param key the public key to use for encryption.
     */
    public BcPublicKeyKeyEncryptionMethodGenerator(PGPPublicKey key)
    {
        super(key);
    }

    /**
     * Provide a user defined source of randomness.
     *
     * @param random the secure random to be used.
     * @return the current generator.
     */
    public BcPublicKeyKeyEncryptionMethodGenerator setSecureRandom(SecureRandom random)
    {
        this.random = random;

        return this;
    }

    protected byte[] encryptSessionInfo(PGPPublicKey pubKey, byte[] sessionInfo)
        throws PGPException
    {
        try
        {
            AsymmetricKeyParameter cryptoPublicKey = keyConverter.getPublicKey(pubKey);
            PublicKeyPacket pubKeyPacket = pubKey.getPublicKeyPacket();
            if (pubKey.getAlgorithm() == PublicKeyAlgorithmTags.ECDH)
            {
                ECDHPublicBCPGKey ecPubKey = (ECDHPublicBCPGKey)pubKeyPacket.getKey();
                byte[] userKeyingMaterial = RFC6637Utils.createUserKeyingMaterial(pubKeyPacket, new BcKeyFingerprintCalculator());
                if (ecPubKey.getCurveOID().equals(CryptlibObjectIdentifiers.curvey25519))
                {
                    AsymmetricCipherKeyPair ephKp = getAsymmetricCipherKeyPair(new X25519KeyPairGenerator(), new X25519KeyGenerationParameters(random));

                    byte[] secret = getSecret(new X25519Agreement(), cryptoPublicKey, ephKp);

                    byte[] ephPubEncoding = new byte[1 + X25519PublicKeyParameters.KEY_SIZE];
                    ephPubEncoding[0] = X_HDR;
                    ((X25519PublicKeyParameters)ephKp.getPublic()).encode(ephPubEncoding, 1);
                    return encryptSessionInfo(sessionInfo, secret, userKeyingMaterial, ephPubEncoding, ecPubKey.getHashAlgorithm(), ecPubKey.getSymmetricKeyAlgorithm());
                }
                else
                {
                    AsymmetricCipherKeyPair ephKp = getAsymmetricCipherKeyPair(new ECKeyPairGenerator(),
                        new ECKeyGenerationParameters(((ECPublicKeyParameters)cryptoPublicKey).getParameters(), random));

                    ECDHBasicAgreement agreement = new ECDHBasicAgreement();
                    agreement.init(ephKp.getPrivate());
                    BigInteger S = agreement.calculateAgreement(cryptoPublicKey);
                    byte[] secret = BigIntegers.asUnsignedByteArray(agreement.getFieldSize(), S);

                    byte[] ephPubEncoding = ((ECPublicKeyParameters)ephKp.getPublic()).getQ().getEncoded(false);

                    return encryptSessionInfo(sessionInfo, secret, userKeyingMaterial, ephPubEncoding, ecPubKey.getHashAlgorithm(), ecPubKey.getSymmetricKeyAlgorithm());
                }
            }
            else if (pubKey.getAlgorithm() == PublicKeyAlgorithmTags.X25519)
            {
                return encryptSessionInfo(pubKeyPacket, sessionInfo, HashAlgorithmTags.SHA256, SymmetricKeyAlgorithmTags.AES_128,
                    new X25519KeyPairGenerator(), new X25519KeyGenerationParameters(random), new X25519Agreement(), cryptoPublicKey, X25519PublicKeyParameters.KEY_SIZE,
                    (publicKey, ephPubEncoding) -> ((X25519PublicKeyParameters)publicKey).encode(ephPubEncoding, 0));
            }
            else if (pubKey.getAlgorithm() == PublicKeyAlgorithmTags.X448)
            {
                return encryptSessionInfo(pubKeyPacket, sessionInfo, HashAlgorithmTags.SHA512, SymmetricKeyAlgorithmTags.AES_256,
                    new X448KeyPairGenerator(), new X448KeyGenerationParameters(random), new X448Agreement(), cryptoPublicKey, X448PublicKeyParameters.KEY_SIZE,
                    (publicKey, ephPubEncoding) -> ((X448PublicKeyParameters)publicKey).encode(ephPubEncoding, 0));
            }
            else
            {
                AsymmetricBlockCipher c = BcImplProvider.createPublicKeyCipher(pubKey.getAlgorithm());

                c.init(true, new ParametersWithRandom(cryptoPublicKey, random));

                return c.processBlock(sessionInfo, 0, sessionInfo.length);
            }
        }
        catch (InvalidCipherTextException e)
        {
            throw new PGPException("exception encrypting session info: " + e.getMessage(), e);
        }
        catch (IOException e)
        {
            throw new PGPException("exception encrypting session info: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ephPubEncodingOperation
    {
        void getEphPubEncoding(AsymmetricKeyParameter publicKey, byte[] ephPubEncoding);
    }

    private byte[] encryptSessionInfo(byte[] sessionInfo, byte[] secret,
                                      byte[] userKeyingMaterial, byte[] ephPubEncoding, int hashAlgorithm, int symmetricKeyAlgorithm)
        throws IOException, PGPException
    {
        RFC6637KDFCalculator rfc6637KDFCalculator = new RFC6637KDFCalculator(
            new BcPGPDigestCalculatorProvider().get(hashAlgorithm), symmetricKeyAlgorithm);
        KeyParameter key = new KeyParameter(rfc6637KDFCalculator.createKey(secret, userKeyingMaterial));

        byte[] paddedSessionData = PGPPad.padSessionData(sessionInfo, sessionKeyObfuscation);

        byte[] C = getWrapper(symmetricKeyAlgorithm, key, paddedSessionData);

        return getSessionInfo(ephPubEncoding, C);
    }

    private byte[] encryptSessionInfo(PublicKeyPacket pubKeyPacket, byte[] sessionInfo, int hashAlgorithm, int symmetricKeyAlgorithm,
                                      AsymmetricCipherKeyPairGenerator gen, KeyGenerationParameters parameters, RawAgreement agreement, AsymmetricKeyParameter cryptoPublicKey,
                                      int keySize, ephPubEncodingOperation ephPubEncodingOperation)
        throws PGPException, IOException
    {
        AsymmetricCipherKeyPair ephKp = getAsymmetricCipherKeyPair(gen, parameters);
        byte[] secret = getSecret(agreement, cryptoPublicKey, ephKp);
        byte[] ephPubEncoding = new byte[keySize];
        ephPubEncodingOperation.getEphPubEncoding(ephKp.getPublic(), ephPubEncoding);
        RFC6637KDFCalculator rfc6637KDFCalculator = new RFC6637KDFCalculator(
            new BcPGPDigestCalculatorProvider().get(hashAlgorithm), symmetricKeyAlgorithm);
        KeyParameter key = new KeyParameter(rfc6637KDFCalculator.createKey(Arrays.concatenate(pubKeyPacket.getKey().getEncoded(), ephPubEncoding, secret)));
        //No checksum and padding
        byte[] sessionData = new byte[sessionInfo.length - 3];
        System.arraycopy(sessionInfo, 1, sessionData, 0, sessionData.length);

        byte[] C = getWrapper(symmetricKeyAlgorithm, key, sessionData);

        return getSessionInfo_25519or448(ephPubEncoding, sessionInfo[0], C);
    }

    private byte[] getWrapper(int symmetricKeyAlgorithm, KeyParameter key, byte[] sessionData)
        throws PGPException
    {
        Wrapper c = BcImplProvider.createWrapper(symmetricKeyAlgorithm);
        c.init(true, new ParametersWithRandom(key, random));
        byte[] C = c.wrap(sessionData, 0, sessionData.length);
        return C;
    }

    private AsymmetricCipherKeyPair getAsymmetricCipherKeyPair(AsymmetricCipherKeyPairGenerator gen, KeyGenerationParameters parameters)
    {
        gen.init(parameters);
        return gen.generateKeyPair();
    }

    private static byte[] getSecret(RawAgreement agreement, AsymmetricKeyParameter cryptoPublicKey, AsymmetricCipherKeyPair ephKp)
    {
        agreement.init(ephKp.getPrivate());
        byte[] secret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(cryptoPublicKey, secret, 0);
        return secret;
    }
}