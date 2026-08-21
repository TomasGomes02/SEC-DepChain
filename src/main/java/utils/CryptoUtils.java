package utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.List;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import common.MessageType;
import consensus.Node;
import logger.Logger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.web3j.crypto.Hash;
import threshsig.GroupKey;
import threshsig.SigShare;
import threshsig.ThresholdSigException;

import transactions.Transaction;

public class CryptoUtils {

    private static final String AES_ALGO = "AES";
    private static final String SIGNATURE_ALGO = "SHA256withRSA";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String HANDSHAKE_ALGORITHM = "DH";

    public static byte[] sign(PrivateKey privateKey, byte[] message){
        Signature signature;
        try {
            signature = Signature.getInstance(SIGNATURE_ALGO);
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean verify(PublicKey publicKey, byte[] message, byte[] signatureBytes) {
        if (signatureBytes == null || signatureBytes.length < 256) {
            Logger.warn(null, "Invalid Signature");
            return false;
        }
        Signature signature;
        try {
             signature = Signature.getInstance(SIGNATURE_ALGO);
             signature.initVerify(publicKey);
             signature.update(message);
             return signature.verify(signatureBytes);
         } catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
             throw new RuntimeException(e);
         }
    }

    public static byte[] genMac(Key key, byte[] data){
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static Boolean verifyMac(Key key, byte[] data, byte[] mac){
        return MessageDigest.isEqual(genMac(key, data), mac);
    }

    public static KeyPair genDHKeyPair(){
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(HANDSHAKE_ALGORITHM);
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static Key genSecret(PrivateKey priv, PublicKey pub){
        KeyAgreement agreement;
        try {
            agreement = KeyAgreement.getInstance(HANDSHAKE_ALGORITHM);
            agreement.init(priv);
            agreement.doPhase(pub, true);
            return new SecretKeySpec(agreement.generateSecret(), 0, 32, AES_ALGO);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    // compute a hash for the node
    public static byte[] computeNodeHash(byte[] parentHash, List<Transaction> transactions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (parentHash != null) digest.update(parentHash);
            if (transactions != null) {
                for (Transaction tx : transactions) {
                    //digest.update(tx.getId().getBytes(StandardCharsets.UTF_8));

                    digest.update(tx.getSender().toString().getBytes(StandardCharsets.UTF_8));

                    digest.update(tx.getReceiver().toString().getBytes(StandardCharsets.UTF_8));

                    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 4);
                    buffer.putLong(tx.getAmount());
                    buffer.putLong(tx.getGasPrice());
                    buffer.putLong(tx.getGasLimit());
                    buffer.putLong(tx.getNonce());
                    digest.update(buffer.array());

                    if (tx.getPayload() != null)
                        digest.update(tx.getPayload().getBytes());

                    digest.update(tx.getSignature());
                }
            }

            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] computeSignHash(MessageType type, int viewNumber, Node node) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            digest.update(type.name().getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(viewNumber);
            digest.update(buffer.array());

            if (node != null && node.getHash() != null) {
                digest.update(node.getHash());
            }

            return digest.digest();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate vote signature", e);
        }
    }

//    public static boolean tverify(SigShare[] sigs, GroupKey groupKey, Object... data) {
//        try {
//            return SigShare.verify(
//                    Utils.serialize(data),
//                    sigs,
//                    groupKey.getK(),
//                    groupKey.getL(),
//                    groupKey.getModulus(),
//                    groupKey.getExponent()
//            );
//        } catch (ThresholdSigException e) {
//            System.err.println("Error validating signature");
//        }
//        return false;
//    }
    public static boolean tverify(SigShare[] sigs, GroupKey groupKey, MessageType type, int viewNumber, Node node) {
        try {
            byte[] signedData = computeSignHash(type, viewNumber, node);

            return SigShare.verify(
                    signedData,
                    sigs,
                    groupKey.getK(),
                    groupKey.getL(),
                    groupKey.getModulus(),
                    groupKey.getExponent()
            );
        } catch (ThresholdSigException e) {
            Logger.error(null, "Error validating signature: ");
            e.printStackTrace();
        }
        return false;
    }

    public static Address generateAddressFromPubKey(PublicKey pub) {
        byte[] pubKeyBytes = pub.getEncoded();
        byte[] hashedKey = Hash.sha3(pubKeyBytes);
        byte[] addressBytes = Arrays.copyOfRange(hashedKey, hashedKey.length - 20, hashedKey.length);
        return Address.wrap(Bytes.wrap(addressBytes));
    }
}
