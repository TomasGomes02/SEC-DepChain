package common;

import utils.CryptoUtils;

import java.security.Key;

public final class HandshakeAck implements Message{
    private final int sender;
    private final byte[] mac;

    public HandshakeAck(int sender, byte[] mac) {
        this.sender = sender;
        this.mac = mac;
    }

    public HandshakeAck(int sender, Key key) {
        this(sender, CryptoUtils.genMac(key, Integer.toString(sender).getBytes()));
    }

    public boolean verifyMac(Key key){
        return CryptoUtils.verifyMac(key, Integer.toString(this.sender).getBytes(), this.mac);
    }
}
