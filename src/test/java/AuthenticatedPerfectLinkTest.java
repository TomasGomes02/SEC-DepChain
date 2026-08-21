import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import communication.AuthenticatedPerfectLink;

import common.Message;
import utils.Utils;

import static org.junit.jupiter.api.Assertions.*;

public class AuthenticatedPerfectLinkTest {

    private DatagramSocket socketA;
    private DatagramSocket socketB;
    private AuthenticatedPerfectLink linkA;
    private AuthenticatedPerfectLink linkB;
    private KeyPair sharedKeyPair;
    private InetSocketAddress addressB;
    private InetSocketAddress addressA;

    @BeforeEach
    void setup() throws Exception {
        socketA = new DatagramSocket(0);
        socketB = new DatagramSocket(0);

        // generate an RSA KeyPair for testing
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        sharedKeyPair = kpg.generateKeyPair();

        addressB = new InetSocketAddress("localhost", socketB.getLocalPort());
        addressA = new InetSocketAddress("localhost", socketA.getLocalPort());

        linkA = new AuthenticatedPerfectLink(socketA, addressB, 1, 2, sharedKeyPair);
        linkB = new AuthenticatedPerfectLink(socketB, addressA, 2, 1, sharedKeyPair);
    }

    @AfterEach
    void shutdown() {
        socketA.close();
        socketB.close();
    }

    // helper method to read from a socket, deserialize, and return the message
    private Message receiveFromSocket(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.setSoTimeout(2000);
        socket.receive(packet);
        return (Message) Utils.deserialize(packet.getData(), packet.getLength());
    }

    @Test
    void testFullHandshakeAndMessageDelivery() throws Exception {
        // mutual handshake
        // both nodes must initiate a handshake to exchange DH public keys
        linkA.beginHandshake();
        linkB.beginHandshake();

        // Node B receives Handshake from Node A
        Message msgAtB = receiveFromSocket(socketB);
        linkB.deliver(msgAtB); 

        // Node A receives Handshake from Node B
        Message msgAtA = receiveFromSocket(socketA);
        linkA.deliver(msgAtA); 

        // Node A receives HandshakeAck from Node B
        Message ackAtA = receiveFromSocket(socketA);
        linkA.deliver(ackAtA);

        // Node B receives HandshakeAck from Node A
        Message ackAtB = receiveFromSocket(socketB);
        linkB.deliver(ackAtB);

        // message exchange
        Message myMessage = new DummyMessage("This is the message"); 

        // Node A sends message to B
        linkA.send(myMessage);

        // Node B receives the AuthenticatedMessage
        Message authMsgAtB = receiveFromSocket(socketB);

        // Node B processes message. 
        boolean finalDeliveryStatus = linkB.deliver(authMsgAtB);
        assertTrue(finalDeliveryStatus, "Message should be delivered");

        // verify message duplication
        boolean duplicateDeliveryStatus = linkB.deliver(authMsgAtB);
        assertFalse(duplicateDeliveryStatus, "Duplicate message should not be delivered again");

        // Node A receives AuthenticatedMessageAck
        Message ackAppMsgAtA = receiveFromSocket(socketA);
        linkA.deliver(ackAppMsgAtA);
    }

    @Test
    void testClientReconnectionUpdatesSessionKey() throws Exception {
        // estabilish connection
        linkA.beginHandshake();
        linkB.beginHandshake();

        Message msgAtB = receiveFromSocket(socketB);
        linkB.deliver(msgAtB); 

        Message msgAtA = receiveFromSocket(socketA);
        linkA.deliver(msgAtA); 

        Message ackAtA = receiveFromSocket(socketA);
        linkA.deliver(ackAtA);

        Message ackAtB = receiveFromSocket(socketB);
        linkB.deliver(ackAtB);

        // save the old session key to prove it changes later
        Key oldSessionKeyB = linkB.getSessionKey();
        assertNotNull(oldSessionKeyB, "Session key should be established");

        // client crashes and restarts
        // simulate a restart by creating a new AuthenticatedPerfectLink on the same socket
        AuthenticatedPerfectLink linkA_restarted = new AuthenticatedPerfectLink(socketA, addressB, 1, 2, sharedKeyPair);
        
        // new client initiates connection
        linkA_restarted.beginHandshake();

        // Node B receives the new handshake
        Message newHandshakeAtB = receiveFromSocket(socketB);
        
        // generate a new session key, and call beginHandshake() to send its DH key back!
        linkB.deliver(newHandshakeAtB);

        // verify the session key changed on Node B
        Key newSessionKeyB = linkB.getSessionKey();
        assertNotNull(newSessionKeyB);
        assertNotEquals(oldSessionKeyB, newSessionKeyB, "Server did not update session key on reconnection");

        // node A will now receive two messages from B: A Handshake (DH Key) and a HandshakeAck
        Message msg1AtA = receiveFromSocket(socketA);
        Message msg2AtA = receiveFromSocket(socketA);

        linkA_restarted.deliver(msg1AtA);
        linkA_restarted.deliver(msg2AtA);

        // verify that the restarted client successfully generated the matching session key!
        assertNotNull(linkA_restarted.getSessionKey(), "Restarted client failed to generate session key");
        assertEquals(newSessionKeyB, linkA_restarted.getSessionKey(), "Client and Server session keys do not match after reconnection");
    }

    private record DummyMessage(String data) implements Message {}
}