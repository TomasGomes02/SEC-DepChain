import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import client.ClientLibrary;
import consensus.BlockchainConsensus;
import consensus.ByzantineBehaviour;
import contracts.BlockchainStateManager;
import logger.Logger;
import secrets.KeyStoreManager;
import utils.CryptoUtils;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

public class TransfersTest {

    private static KeyStoreManager secrets;
    private static Address client10; // sender
    private static Address client11; // receiver
    private static List<Thread> memberThreads = new ArrayList<>();
    private static List<BlockchainConsensus> activeServers = new ArrayList<>();

    @BeforeAll
    public static void setup() throws Exception {
        Logger.test(null, "--- Setting up DepCoin and ISTCoin Transfer Test ---");

        for (int i = 1; i <= 4; i++) {
            File dirtyState = new File("src/main/java/contracts/state" + i + ".json");
            if (dirtyState.exists()) dirtyState.delete(); 
        }

        // load keys from the keystore
        File keyStoreFile = new File("all_members.p12");
        secrets = new KeyStoreManager(keyStoreFile, "changeit".toCharArray());

        KeyPair client10Keys = new KeyPair(
                secrets.getPublicKey("client10_pub"),
                secrets.getPrivateKey("client10_priv")
        );
        KeyPair client11Keys = new KeyPair(
                secrets.getPublicKey("client11_pub"),
                secrets.getPrivateKey("client11_priv")
        );

        // generate addresses
        client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());
        client11 = CryptoUtils.generateAddressFromPubKey(client11Keys.getPublic());

        Logger.test(null, "client10 (Client 10): " + client10.toHexString());
        Logger.test(null, "client11 (Client 11): " + client11.toHexString() + "\n");

        // start members
        Logger.test(null, "Starting Blockchain Consensus Members...");

        activeServers.clear(); 
        memberThreads.clear();

        for (int i = 1; i <= 4; i++) {
            InetSocketAddress addr = new InetSocketAddress("localhost", 8000 + i);
            BlockchainConsensus server = new BlockchainConsensus(i, addr, new DatagramSocket(8000 + i), secrets, ByzantineBehaviour.NONE);
            activeServers.add(server);
        }

        for (BlockchainConsensus member : activeServers) {
            for (BlockchainConsensus memberToAdd : activeServers) {
                member.getState().addMember(memberToAdd);
            }
            for (int clientId = 10; clientId < 14; clientId++) {
                int clientPort = 5000 + clientId;
                member.addClientConnection(clientId, new InetSocketAddress("localhost", clientPort));
            }
            member.createConnections();
            
            Thread t = new Thread(member);
            memberThreads.add(t);
            t.start();
        }

        Thread.sleep(500);

    }

    @AfterAll
    public static void shutDown() {
        for (BlockchainConsensus server : activeServers) {
            server.stop(); 
        }
        for (Thread t : memberThreads) {
            t.interrupt();
        }
    }

    @Test
    public void testTransfers() {
        ClientLibrary client = null;
        try {
            // state manager to verify the results
            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            // get balances before the transfer
            long initialSenderDep = stateManager.getBalances(client10).get("DEP");
            long initialReceiverDep = stateManager.getBalances(client11).get("DEP");
            long initialSenderIst = stateManager.getBalances(client10).get("IST");
            long initialReceiverIst = stateManager.getBalances(client11).get("IST");

            Logger.test(null, "Initial client10 DEP: " + initialSenderDep + " | IST: " + initialSenderIst);
            Logger.test(null, "Initial client11 DEP:   " + initialReceiverDep + " | IST: " + initialReceiverIst);

            // setup client 10
            DatagramSocket clientSocket = new DatagramSocket(5010);
            client = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), clientSocket, secrets);
            client.start();
            client.send("balance");

            Thread.sleep(500);

            // send DepCoin transfer 1
            long transferAmountDep = 1000;
            long gasLimit = 450000;
            long gasPrice = 10;
            
            String commandDep = String.format("transferDEP %s %d %d %d", client11.toHexString(), transferAmountDep, gasLimit, gasPrice);
            Logger.test(null, "Executing: " + commandDep);
            client.send(commandDep);

            // send ISTCoin transfer 
            long transferAmountIst = 500;
            String commandIst = String.format("transferIST %s %d %d %d", client11.toHexString(), transferAmountIst, gasLimit, gasPrice);
            Logger.test(null, "Executing: " + commandIst);
            client.send(commandIst);

            // wait for consensus to finish appending the block
            Logger.test(null, "Waiting 5 seconds for consensus...");
            Thread.sleep(5000);

            // reload the world
            stateManager.loadWorld(); 
            long finalSenderDep = stateManager.getBalances(client10).get("DEP");
            long finalReceiverDep = stateManager.getBalances(client11).get("DEP");
            long finalSenderIst = stateManager.getBalances(client10).get("IST");
            long finalReceiverIst = stateManager.getBalances(client11).get("IST");

            // check balances
            Logger.test(null, "Final client10 DEP: " + finalSenderDep + " | IST: " + finalSenderIst);
            Logger.test(null, "Final client11 DEP:   " + finalReceiverDep + " | IST: " + finalReceiverIst);

            // the receiver should have initial + 1000 DEP
            assertEquals(initialReceiverDep + transferAmountDep, finalReceiverDep, "Receiver did not get the correct amount of DepCoin!");

            // the sender should have initial - 1000 - gasFee (for two transactions)
            assertTrue(finalSenderDep < initialSenderDep - transferAmountDep, "Sender was not charged gas or transfer amount!");

            // the receiver should have initial + 500 IST
            assertEquals(initialReceiverIst + transferAmountIst, finalReceiverIst, "Receiver did not get the correct amount of ISTCoin!");

            // the sender should have initial - 500 IST
            assertEquals(initialSenderIst - transferAmountIst, finalSenderIst, "Sender ISTCoin balance did not decrease correctly!");

            Logger.test(null, "PASS: Both DepCoin and ISTCoin transferred successfully and appended in a block.");

        } catch (Exception e) {
            fail("Exception thrown during Transfer Test: " + e.getMessage());
        } finally {
            // close the client socket
            if (client != null) {
                client.stop();
            }
        }
    }
}