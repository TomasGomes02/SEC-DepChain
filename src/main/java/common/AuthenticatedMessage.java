package common;

import utils.CryptoUtils;
import utils.Utils;

import java.security.Key;

public final class AuthenticatedMessage implements Message{

    private final int sequenceNumber;
    private final int sender;
    private final Object msg;
    private final byte[] mac;

    public AuthenticatedMessage(int sequenceNumber, int sender, Object msg, byte[] mac){
        this.sequenceNumber = sequenceNumber;
        this.sender = sender;
        this.msg = msg;
        this.mac = mac;
    }

    public AuthenticatedMessage(int sequenceNumber, int sender, Object msg, Key key){
        this(sequenceNumber, sender, msg, CryptoUtils.genMac(key, Utils.serialize(
                new Object[]{sequenceNumber, sender, msg})));
    }

    public boolean verifyMac(Key key){
        return CryptoUtils.verifyMac(key, Utils.serialize(new Object[]{sequenceNumber, sender, msg}), this.mac);
    }

    public int getSender(){
        return this.sender;
    }

    public int getSequenceNumber(){
        return this.sequenceNumber;
    }

    public Object getMsg() {
        return this.msg;
    }
}
