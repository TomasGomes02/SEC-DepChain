package common;

import utils.CryptoUtils;

import java.security.Key;

public final class AuthenticatedMessageAck implements Message {

    private final int sequenceNumber;
    private final byte[] mac;

    public AuthenticatedMessageAck(int sequenceNumber, byte[] mac) {
        this.sequenceNumber = sequenceNumber;
        this.mac = mac;
    }

    public AuthenticatedMessageAck(int sequenceNumber, Key key){
        this(sequenceNumber, CryptoUtils.genMac(key, new byte[]{(byte) sequenceNumber}));
    }

    public boolean verifyMac(Key key){
        return CryptoUtils.verifyMac(key, new byte[]{(byte) sequenceNumber}, this.mac);
    }

    public int getSequenceNumber(){
        return this.sequenceNumber;
    }
}
