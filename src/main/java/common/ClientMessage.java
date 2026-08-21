package common;

import java.security.KeyPair;
import java.security.PublicKey;

import transactions.Transaction;
import utils.CryptoUtils;
import utils.Utils;

public final class ClientMessage implements Message {
    private final int id;
    private final Transaction transaction;
    private final byte[] signature;
    private final long nonce;
    private final KeyPair keyPair;

    public ClientMessage(int id, Transaction transaction, long nonce, KeyPair keyPair) {
        this.id = id;
        byte[] message = Utils.serialize(new Object[]{transaction, nonce , keyPair.getPublic()});
        this.transaction = transaction;
        this.signature = CryptoUtils.sign(keyPair.getPrivate(), message);
        this.nonce = nonce;
        this.keyPair = keyPair;
    }

    public int getId() {
        return this.id;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public byte[] getSignature() {
        return signature;
    }

    public long getNonce() {
        return nonce;
    }

    public PublicKey getPublicKey() {
        return this.keyPair.getPublic();
    }
}
