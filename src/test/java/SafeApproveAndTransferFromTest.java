import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

public class SafeApproveAndTransferFromTest {

    private static KeyStoreManager secrets;
    private static Address client10; // sender (owner of IST Coins)
    private static Address client11; // spender
    private static Address client12; // receiver
    private static List<Thread> memberThreads = new ArrayList<>();
    private static List<BlockchainConsensus> activeServers = new ArrayList<>();

    @BeforeEach
    public void setup() throws Exception {
        Logger.test(null, "--- Setting up transferFrom Test ---");
        for (int i = 1; i <= 4; i++) {
            File dirtyState = new File("src/main/java/contracts/state" + i + ".json");
            if (dirtyState.exists()) dirtyState.delete(); 
        }

        // load keys from the keystore
        File keyStoreFile = new File("all_members.p12");
        secrets = new KeyStoreManager(keyStoreFile, "changeit".toCharArray());

        KeyPair client10Keys = new KeyPair(secrets.getPublicKey("client10_pub"), secrets.getPrivateKey("client10_priv"));
        KeyPair client11Keys = new KeyPair(secrets.getPublicKey("client11_pub"), secrets.getPrivateKey("client11_priv"));
        KeyPair client12Keys = new KeyPair(secrets.getPublicKey("client12_pub"), secrets.getPrivateKey("client12_priv"));

        // generate addresses
        client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());
        client11 = CryptoUtils.generateAddressFromPubKey(client11Keys.getPublic());
        client12 = CryptoUtils.generateAddressFromPubKey(client12Keys.getPublic());

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
    }

    @AfterEach
    public void shutDown() {
        for (BlockchainConsensus server : activeServers) {
            server.stop(); 
        }
        for (Thread t : memberThreads) {
            t.interrupt();
        }
    }

    @Test
    public void testUnallowedTransferFrom() {
        // because we are doing this test without SafeApprove
        // the transferFrom is supposed to not go through

        ClientLibrary client11CL = null;
        try {
            Logger.test(null, "--- Running Unallowed transferFrom Test ---");

            // load state manager
            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            long initialClient10IST = stateManager.getBalances(client10).get("IST");
            long initialClient12IST = stateManager.getBalances(client12).get("IST");
            long initialClient11DEP = stateManager.getBalances(client11).get("DEP");

            Logger.test(null, "Initial Client 10 IST: " + initialClient10IST);
            Logger.test(null, "Initial Client 12 IST: " + initialClient12IST);
            Logger.test(null, "Initial Client 11 DEP: " + initialClient11DEP);

            // setup client 11
            DatagramSocket client11Socket = new DatagramSocket(5011);
            client11CL = new ClientLibrary(11, new InetSocketAddress("localhost", 5011), client11Socket, secrets);
            client11CL.start();
            client11CL.send("balance");

            Thread.sleep(500); // wait for handshakes

            long gasLimit = 450000;
            long gasPrice = 10;
            
            // client 11 calls transferFrom without approval
            String noApproveCmd = String.format("transferFrom %s %s 50 %d %d", 
                client10.toHexString(), client12.toHexString(), gasLimit, gasPrice);
            Logger.test(null, "Client 11 executing unapproved transfer: " + noApproveCmd);
            client11CL.send(noApproveCmd);

            Logger.test(null, "Waiting 5 seconds for Block consensus...");
            Thread.sleep(5000);

            stateManager.loadWorld(); 
            long finalClient10IST = stateManager.getBalances(client10).get("IST");
            long finalClient12IST = stateManager.getBalances(client12).get("IST");
            long finalClient11DEP = stateManager.getBalances(client11).get("DEP");

            Logger.test(null, "Final Client 10 IST: " + finalClient10IST);
            Logger.test(null, "Final Client 12 IST: " + finalClient12IST);
            Logger.test(null, "Final Client 11 DEP: " + finalClient11DEP);

            // balances must not change due to EVM revertal
            assertEquals(initialClient10IST, finalClient10IST, "SECURITY FAILURE: Client 10 IST was transfered without approval");
            assertEquals(initialClient12IST, finalClient12IST, "SECURITY FAILURE: Client 12 received unapproved IST");

            // client 11 must still pay gas for the failed attempt
            assertTrue(finalClient11DEP < initialClient11DEP, "Client 11 was not charged Gas for the failed Smart Contract call");

            Logger.test(null, "PASS: Unapproved transferFrom correctly reverted and charged gas!");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client11CL != null) client11CL.stop();
        }
    }

    @Test
    public void testSafeApproveAndTransferFrom() {
        ClientLibrary client10CL = null;
        ClientLibrary client11CL = null;
        try {
            // state manager to verify the results
            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            // get balances before the transfer
            long initialClient10IST = stateManager.getBalances(client10).get("IST");
            long initialClient12IST = stateManager.getBalances(client12).get("IST");

            Logger.test(null, "Initial Client 10 IST: " + initialClient10IST);
            Logger.test(null, "Initial Client 12 IST: " + initialClient12IST);

            // Client 10 approves Client 11 for 100 IST
            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            client10CL.start();
            client10CL.send("balance");
            Thread.sleep(500); // wait for handshake

            long gasLimit = 450000;
            long gasPrice = 10;
            
            // Client 10 calls safeApprove(Client11, current=0, new=100)
            String approveCmd = String.format("safeApprove %s 0 100 %d %d", client11.toHexString(), gasLimit, gasPrice);
            Logger.test(null, "Client 10 executing: " + approveCmd);
            client10CL.send(approveCmd);

            Logger.test(null, "Waiting 5 seconds for Block 1 consensus...");
            Thread.sleep(5000);

            // Client 11 transfers 50 IST from Client 10 to Client 12
            DatagramSocket client11Socket = new DatagramSocket(5011);
            client11CL = new ClientLibrary(11, new InetSocketAddress("localhost", 5011), client11Socket, secrets);
            client11CL.start();
            client11CL.send("balance");
            Thread.sleep(500); // wait for handshake

            // Client 11 calls transferFrom(From: Client10, To: Client12, Amount: 50)
            String transferFromCmd = String.format("transferFrom %s %s 50 %d %d", 
                client10.toHexString(), client12.toHexString(), gasLimit, gasPrice);
            Logger.test(null, "Client 11 executing: " + transferFromCmd);
            client11CL.send(transferFromCmd);

            Logger.test(null, "Waiting 5 seconds for Block 2 consensus...");
            Thread.sleep(5000);

            // verify final balances
            stateManager.loadWorld(); 
            long finalClient10IST = stateManager.getBalances(client10).get("IST");
            long finalClient12IST = stateManager.getBalances(client12).get("IST");

            Logger.test(null, "Final Client 10 IST: " + finalClient10IST);
            Logger.test(null, "Final Client 12 IST: " + finalClient12IST);

            // because we used DEP for the dummy transfers, IST balances must be exactly 50 apart
            assertEquals(initialClient10IST - 50, finalClient10IST, "Client 10's IST balance did not decrease by 50!");
            assertEquals(initialClient12IST + 50, finalClient12IST, "Client 12 did not receive the 50 IST from Client 11's transferFrom!");

            Logger.test(null, "PASS: safeApprove and transferFrom executed correctly!");

        } catch (Exception e) {
            fail("Exception thrown during SafeApprove and TransferFrom Test: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
            if (client11CL != null) client11CL.stop();
        }
    }
}
