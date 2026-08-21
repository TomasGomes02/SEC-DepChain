package communication;

import common.*;
import logger.Logger;
import utils.CryptoUtils;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AuthenticatedPerfectLink {

    protected DatagramSocket socket;
    protected SocketAddress address;
    private final StubbornLink sl;
    private final Map<Integer, AtomicBoolean> cancellations;
    private final Set<Integer> delivered;
    private final ArrayList<Message> pendingMessages;
    private final AtomicInteger sequenceNumber;
    private final int sender;
    private final int receiver;
    private final KeyPair keyPairConnection;
    private final KeyPair keyPairDH;

    private AtomicBoolean isCanceled;
    private Key sessionKey;

    public AuthenticatedPerfectLink(DatagramSocket socket, SocketAddress address, int sender, int receiver,
                                    KeyPair keyPairConnection) throws IOException {
        this.sl = new StubbornLink(socket, address);
        this.address = address;
        this.socket = socket;
        this.cancellations = new HashMap<>();
        this.delivered = new HashSet<>();
        this.pendingMessages = new ArrayList<>();
        this.sequenceNumber = new AtomicInteger(0);
        this.sender = sender;
        this.receiver = receiver;
        this.keyPairConnection = keyPairConnection;
        this.keyPairDH = CryptoUtils.genDHKeyPair();
        this.sessionKey = null;
        this.isCanceled = null;
    }

    public void send(Object msg){
        if(sessionKey == null) {
            this.pendingMessages.add((Message) msg);
            return;
        }
        int sequenceNumber = this.sequenceNumber.getAndIncrement();
        AuthenticatedMessage message = new AuthenticatedMessage(sequenceNumber, this.sender, msg, this.sessionKey);
        AtomicBoolean cancellation = this.sl.send(message);
        cancellations.put(sequenceNumber, cancellation);
    }

    public boolean deliver(Message message) throws IOException, ClassNotFoundException {
        return switch (message) {
            case Handshake handshake -> {
                processHandshake(handshake);
                yield false;
            }
            case HandshakeAck ack -> {
                processHandshakeAck(ack);
                yield false;
            }
            case AuthenticatedMessageAck ack -> {
                if (this.sessionKey == null) {
                    yield false;
                }
                processAck(ack);
                yield false;
            }
            case AuthenticatedMessage msg -> {
                if (this.sessionKey == null) {
                    yield false;
                }
                yield processMessage(msg);
            }
            default -> false;
        };
    }

    public void beginHandshake(){
        PrivateKey priv = this.keyPairConnection.getPrivate();
        PublicKey pub = this.keyPairDH.getPublic();
        Handshake handshake = new Handshake(this.sender, pub, priv);
        this.isCanceled = this.sl.send(handshake);
    }

    private void processHandshake(Handshake handshake){
        if (!handshake.verifySignature(this.keyPairConnection.getPublic())) {
            Logger.warn(String.valueOf(this.sender), "Rejected Handshake from " + handshake.sender() + " due to invalid signature.");
            return;
        }

        boolean isReconnection = (this.sessionKey != null);
        this.sessionKey = CryptoUtils.genSecret(this.keyPairDH.getPrivate(), handshake.pubKey());
        
        if (isReconnection) {
            Logger.info(String.valueOf(this.sender), "Session key updated for reconnection with " + handshake.sender());
        } else {
            Logger.info(String.valueOf(this.sender), "Session key successfully generated for connection with " + handshake.sender());
        }

        if (this.isCanceled == null || isReconnection) {
            beginHandshake();
        }

        for (Message m : pendingMessages){
            send(m);
        }

        this.sl.send(new HandshakeAck(this.sender, this.sessionKey), 1);
        Logger.debug(String.valueOf(this.sender), "Sent HandshakeAck to " + handshake.sender());
    }

    private void processHandshakeAck(HandshakeAck handshakeAck){
        if(this.sessionKey != null && handshakeAck.verifyMac(this.sessionKey)){
            this.isCanceled.set(true);
        }
    }

    private void processAck(AuthenticatedMessageAck msgAck){
        if(msgAck.verifyMac(this.sessionKey)){
            if(cancellations.containsKey(msgAck.getSequenceNumber())){
                cancellations.get(msgAck.getSequenceNumber()).set(true);
            }
        }
    }

    private boolean processMessage(AuthenticatedMessage msg){
        if(msg.verifyMac(this.sessionKey)){
            if(msg.getSender() == this.receiver){
                this.sl.send(new AuthenticatedMessageAck(msg.getSequenceNumber(), this.sessionKey), 1);

                if (!delivered.contains(msg.getSequenceNumber())) {
                    delivered.add(msg.getSequenceNumber());
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public SocketAddress getSocketAddress(){
        return this.address;
    }

    public int getPeerId() {
        return this.receiver;
    }

    public Key getSessionKey() {
        return sessionKey;
    }
}
