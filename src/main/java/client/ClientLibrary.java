package client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.*;

import org.hyperledger.besu.datatypes.Address;

import common.AuthenticatedMessage;
import common.ClientMessage;
import common.ClientReplyMessage;
import common.Message;
import communication.AuthenticatedPerfectLink;
import consensus.ByzantineBehaviour;
import logger.Logger;
import secrets.KeyStoreManager;
import transactions.Transaction;
import utils.CryptoUtils;
import utils.Utils;


public class ClientLibrary {
    private static class MemberInfo {
        int id;
        SocketAddress socketAddress;
        
        public MemberInfo(int id, int port) {
            this.id = id;
            this.socketAddress = new InetSocketAddress("localhost", port);
        }
    }

    private long nonce;
    private final int id;
    private final DatagramSocket socket;
    private final List<MemberInfo> knownMembers;
    private final Map<Integer, AuthenticatedPerfectLink> connections;
    private final SocketAddress socketAddress;
    private final KeyStoreManager secrets;
    private final Address clientAddress;
    private final List<Address> otherClientAddresses;
    // volatile to be sure other threads see the change
    private volatile boolean running = true;
    private List<String> responses;
    
    private final Address IST_COIN_CONTRACT_ADDRESS = Address.fromHexString("0x1111111111111111111111111111111111111111");
    static final String SEL_TRANSFER      = "a9059cbb";
    static final String SEL_TRANSFER_FROM = "23b872dd";
    static final String SEL_SAFE_APPROVE  = "f6503662";
    private String behaviour;

    public ClientLibrary(int id, SocketAddress socketAddress, DatagramSocket socket, KeyStoreManager secrets) {
        this.id = id;
        this.socket = socket;
        this.socketAddress = socketAddress;
        this.knownMembers = new ArrayList<>();
        this.connections = new HashMap<>();
        this.secrets = secrets;
        this.clientAddress = CryptoUtils.generateAddressFromPubKey(secrets.getPublicKey("client" + id + "_pub"));
        this.otherClientAddresses = generateOtherClientAddresses();
        this.responses = new ArrayList<>();
        this.behaviour = "None";
        knownMembers.add(new MemberInfo(1, 8001));
        knownMembers.add(new MemberInfo(2, 8002));
        knownMembers.add(new MemberInfo(3, 8003));
        knownMembers.add(new MemberInfo(4, 8004));
    }

    public void createConnections() {
        this.knownMembers.forEach(
            m -> {
                try {
                    KeyPair keyPair = new KeyPair(
                            secrets.getPublicKey("member"+ m.id + "_pub"),
                            secrets.getPrivateKey("client"+ id + "_priv"));
                    this.connections.put(m.id, new AuthenticatedPerfectLink(this.socket, m.socketAddress, this.id,
                            m.id, keyPair));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        );
    }


    private void incrementNonce() {
        this.nonce++;
    }

    private void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public int getId() {
        return id;
    }

    public SocketAddress getSocketAddress() {
        return this.socketAddress;
    }

    public void stop() {
        this.running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public void start() {
        createConnections();

        this.connections.values().forEach(AuthenticatedPerfectLink::beginHandshake);

        // start a background listening thread
        new Thread(() -> {
            while (running) {
                byte[] buffer = new byte[65535];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    AuthenticatedPerfectLink apl = getConnection(packet.getSocketAddress());
                    
                    if (apl != null) {
                        Object message = Utils.deserialize(packet.getData(), packet.getLength());
                        if (message instanceof Message) {
                            if (apl.deliver((Message) message)) {
                                if (message instanceof AuthenticatedMessage authMsg) {

                                    Object payload = authMsg.getMsg();

                                    if (payload instanceof ClientReplyMessage(String content)) {
                                        String validatedReply = validateReply(content);
                                        if (validatedReply != null) {
                                            if (validatedReply.contains("Balance")) {
                                                String[] lines = validatedReply.split("\n");
                                                for (String line : lines) {
                                                    if (line.startsWith("Nonce:")) {
                                                        setNonce(Long.parseLong(line.split(":")[1].trim())); // updates client nonce
                                                    }
                                                }
                                                System.out.println(validatedReply);
                                                continue;
                                            }
                                            if (validatedReply.contains("[Error]"))
                                                Logger.error("" + this.id, validatedReply);
                                            else
                                                Logger.info("" + this.id, "[CONSENSUS REACHED] " + validatedReply);
                                            System.out.print("DepChain (ID:" + this.id + ")> ");
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        Logger.error("" + this.id, "Error in listener: " + e.getMessage());
                        e.printStackTrace();
                    } else {
                        Logger.debug("" + this.id, "Socket closed during logout.");
                    }
                }
            }
        }).start();
    }

    private String validateReply(String reply) {
        responses.add(reply);
        if (responses.size() >= 3) {
            String ret = responses.stream()
                    .filter(i -> Collections.frequency(responses, i) >= 2)
                    .findFirst()
                    .orElse(null);

            responses.clear();
            return ret;
        }
        return null;
    }

    // method to match incoming packets to the right connection
    private AuthenticatedPerfectLink getConnection(SocketAddress address){
        for (AuthenticatedPerfectLink link : this.connections.values()) {
            if (link.getSocketAddress().equals(address))
                return link;
        }
        return null;
    }

    public void send(String req) {
        String[] parts = req.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;

        String cmd = parts[0];

        Address from = clientAddress;
        Address to = null;
        String payload = null;
        long amountDepCoin = 0;
        long amountIST = 0;
        long amountFrom = 0;
        int currentVal = 0;
        int newVal = 0;
        long gasLimit = 0;
        long gasPrice = 0;

        try {
            switch (cmd) {
                case "list":
                    printAvailableAddresses();
                    return;
                case "balance":
                    requestBalance();
                    return;
                case "behaviour":
                    if (parts.length != 2) {
                        System.out.println("Usage: behaviour <None | Replay | Signature>");
                        return;
                    }
                    String behaviour = parts[1];

                    if (switch (behaviour.toLowerCase()) {
                        case "none", "replay", "signature" -> true;
                        default -> false;
                    }) {
                        this.behaviour = behaviour;
                        System.out.println("[BYZANTINE] Behaviour set to " + behaviour);
                    }
                    return;
                case "transferIST":
                    if (parts.length != 5) {
                        System.out.println("Usage: transferIST <receiverAddress> <amount> <gasLimit> <gasPrice>");
                        return;
                    }
                    String receiverIST = parts[1];
                    amountIST = Long.parseLong(parts[2]);
                    gasLimit = Long.parseLong(parts[3]);
                    gasPrice = Long.parseLong(parts[4]);

                    if (!otherClientAddresses.contains(Address.fromHexString(receiverIST))) {
                        System.out.println("Receiver address does not exist. Try again.");
                        return;
                    }

                    if (clientAddress.toHexString().equalsIgnoreCase(receiverIST)) {
                        System.out.println("Receiver address cannot be equal to sender address. Try again.");
                        return;
                    }


                    if (amountIST <= 0 || gasLimit <= 0 || gasPrice <= 0) {
                        System.out.println("Transfer amount, gasLimit and gasPrice must be greater than 0. Try again.");
                        return;
                    }

                    to = IST_COIN_CONTRACT_ADDRESS;
                    payload = SEL_TRANSFER +
                            Utils.padHexStringTo256Bit(receiverIST) +
                            Utils.convertLongToHex256Bit(amountIST);
                    break;

                case "transferDEP":
                    if (parts.length != 5) {
                        System.out.println("Usage: transferDEP <receiverAddress> <amount> <gasLimit> <gasPrice>");
                        return;
                    }
                    String receiverDEP = parts[1];
                    amountDepCoin = Long.parseLong(parts[2]);
                    gasLimit = Long.parseLong(parts[3]);
                    gasPrice = Long.parseLong(parts[4]);
                    
                    if (!otherClientAddresses.contains(Address.fromHexString(receiverDEP))) {
                        System.out.println("Receiver address does not exist. Try again.");
                        return;
                    }

                    if (clientAddress.toHexString().equalsIgnoreCase(receiverDEP)) {
                        System.out.println("Receiver address cannot be equal to sender address. Try again.");
                        return;
                    }


                    if (amountDepCoin <= 0 || gasLimit <= 0 || gasPrice <= 0) {
                        System.out.println("Transfer amount, gasLimit and gasPrice must be greater than 0. Try again.");
                        return;
                    }
                    
                    to = Address.fromHexString(receiverDEP);
                    break;

                case "transferFrom":
                    if (parts.length != 6) {
                        System.out.println("Usage: transferFrom <senderAddress> <receiverAddress> <amount> <gasLimit> <gasPrice>");
                        return;
                    }
                    String senderFrom = parts[1];
                    String receiverFrom = parts[2];
                    amountFrom = Long.parseLong(parts[3]);
                    gasLimit = Long.parseLong(parts[4]);
                    gasPrice = Long.parseLong(parts[5]);

                    if (senderFrom.equalsIgnoreCase(receiverFrom)) {
                        System.out.println("Receiver address cannot be equal to sender address. Try again.");
                        return;
                    }

                    if (!otherClientAddresses.contains(Address.fromHexString(receiverFrom)) || 
                        !otherClientAddresses.contains(Address.fromHexString(senderFrom))) {
                        System.out.println("Sender or Receiver address does not exist. Try again.");
                        return;
                    }


                    if (amountFrom <= 0 || gasLimit <= 0 || gasPrice <= 0) {
                        System.out.println("Transfer amount, gasLimit and gasPrice must be greater than 0. Try again.");
                        return;
                    }

                    to = IST_COIN_CONTRACT_ADDRESS;
                    payload = SEL_TRANSFER_FROM +
                            Utils.padHexStringTo256Bit(senderFrom) +
                            Utils.padHexStringTo256Bit(receiverFrom) +
                            Utils.convertLongToHex256Bit(amountFrom);
                    break;

                case "safeApprove":
                    if (parts.length != 6) {
                        System.out.println("Usage: safeApprove <spenderAddress> <currentValue> <newValue> <gasLimit> <gasPrice>");
                        return;
                    }
                    String spender = parts[1];
                    currentVal = Integer.parseInt(parts[2]);
                    newVal = Integer.parseInt(parts[3]);
                    gasLimit = Long.parseLong(parts[4]);
                    gasPrice = Long.parseLong(parts[5]);

                    if (!otherClientAddresses.contains(Address.fromHexString(spender))) {
                        System.out.println("Spender address does not exist. Try again.");
                        return;
                    }

                    if (clientAddress.toHexString().equalsIgnoreCase(spender)) {
                        System.out.println("Spender address cannot be equal to sender address. Try again.");
                        return;
                    }

                    if (gasLimit <= 0 || gasPrice <= 0) {
                        System.out.println("gasLimit, and gasPrice must be greater than 0. Try again.");
                        return;
                    }

                    if (currentVal < 0 || newVal < 0) {
                        System.out.println("currentVal and newVal must be positive. Try again.");
                        return;
                    }
                    

                    to = IST_COIN_CONTRACT_ADDRESS;
                    payload = SEL_SAFE_APPROVE +
                            Utils.padHexStringTo256Bit(spender) +
                            Utils.convertIntegerToHex256Bit(currentVal) +
                            Utils.convertIntegerToHex256Bit(newVal);
                    break;

                default:
                    System.out.println("Unknown command. Type 'help' to see available commands.");
                    return;
            }
        } catch (NumberFormatException e) {
            Logger.error("" + this.id, "Invalid number format. Amount, current value, new value, gas limit, and gas price must be valid numbers.");
            return;
        } catch (Exception e) {
            Logger.error("" + this.id, "Couldn't process command: " + e.getMessage());
            return;
        }

        KeyPair keyPair = new KeyPair(
                secrets.getPublicKey("client"+ id + "_pub"),
                secrets.getPrivateKey("client"+ id + "_priv"));

        Transaction transaction = new Transaction(null, from, to, amountDepCoin, gasPrice, gasLimit, nonce, payload,
                keyPair.getPublic());
        byte[] signature = null;
        if (this.behaviour.equalsIgnoreCase("signature")) { // byzantine behaviour (wrong signature)
            try {
                // generate a random, fake key pair and use it to sign
                java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                java.security.KeyPair fakeKeyPair = kpg.generateKeyPair();
            
                signature = CryptoUtils.sign(fakeKeyPair.getPrivate(), transaction.getId().getBytes());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else // normal procedure
            signature = CryptoUtils.sign(keyPair.getPrivate(), transaction.getId().getBytes());
        
        transaction.setSignature(signature);
        ClientMessage message = new ClientMessage(this.id, transaction, this.nonce, keyPair);

        if (!this.behaviour.equalsIgnoreCase("replay")) {
            this.incrementNonce();
        }
        this.connections.values().forEach(link -> link.send(message));
    }

    private List<Address> generateOtherClientAddresses() {
        List<Address> otherAddresses = new ArrayList<>();
        for (int i = 10; i <= 13; i++) {
            try {
                PublicKey pub = secrets.getPublicKey("client" + i + "_pub");
                if (pub != null) {
                    Address addr = CryptoUtils.generateAddressFromPubKey(pub);
                    otherAddresses.add(addr);
                }
            } catch (Exception e) {
                Logger.error("" + this.id, "Key not found for client" + i);
            }
        }
        return otherAddresses;
    }

    private void printAvailableAddresses() {
        System.out.println("\n=== KNOWN ADDRESSES ===");
        System.out.println("ISTCoin Contract : " + IST_COIN_CONTRACT_ADDRESS.toHexString());

        System.out.println("\n=== CLIENT ADDRESSES ===");
        for (int i = 0; i < otherClientAddresses.size(); i++) {
            int otherClientId = i + 10;
            Address addr = otherClientAddresses.get(i);
            String suffix = (otherClientId == this.id) ? "  <-- (You)" : "";
            System.out.println("Client " + otherClientId + " : " + addr.toHexString() + suffix);
        }
        System.out.println("=======================\n");
    }

    private void requestBalance() {
        KeyPair keyPair = new KeyPair(
                secrets.getPublicKey("client" + id + "_pub"),
                secrets.getPrivateKey("client" + id + "_priv"));

        ClientMessage query = new ClientMessage(this.id, null, this.nonce, keyPair);

        for (int memberId = 1; memberId <= 4; memberId++) {
            AuthenticatedPerfectLink link = connections.get(memberId);
            if (link != null) {
                link.send(query);
            } else {
                Logger.error("" + this.id, "No connection to Member" + memberId + "available.");
            }
        }
    }

    public void setByzantineBehaviour(String behaviour) {
        this.behaviour = behaviour;
    }
}
