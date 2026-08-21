package common;

import utils.CryptoUtils;
import utils.Utils;

import java.security.PrivateKey;
import java.security.PublicKey;

public final class Handshake implements Message {
    private final int sender;
    private final PublicKey pub;
    private final byte[] sig;

    public Handshake(int sender, PublicKey pub, byte[] sig) {
        this.sender = sender;
        this.pub = pub;
        this.sig = sig;
    }

    public Handshake(int sender, PublicKey pub, PrivateKey priv) {
        this(sender, pub, CryptoUtils.sign(priv, Utils.serialize(new Object[]{sender, pub})));
    }

    public int sender() { return sender; }
    public PublicKey pubKey() { return pub; }
    public byte[] signature() { return sig; }

    public boolean verifySignature(PublicKey connKey) {
        return CryptoUtils.verify(connKey, Utils.serialize(new Object[]{sender, pub}), this.sig);
    }

    @Override
    public String toString() {
        return String.format("%s[sender=%s]", getClass().getSimpleName(), this.sender);
    }

}