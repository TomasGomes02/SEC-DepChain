import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import client.ClientLibrary;
import consensus.BlockchainConsensus;
import contracts.BlockchainStateManager;
import logger.Logger;
import consensus.ByzantineBehaviour;
import secrets.KeyStoreManager;
import utils.CryptoUtils;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

public class InsufficientFundsTest {

    private static KeyStoreManager secrets;
    private static Address client10;
    private static Address client11;
    private List<Thread> memberThreads = new ArrayList<>();
    private List<BlockchainConsensus> activeServers = new ArrayList<>();

    @BeforeEach
    public void setup() throws Exception {
        for (int i = 1; i <= 4; i++) {
            File dirtyState = new File("src/main/java/contracts/state" + i + ".json");
            if (dirtyState.exists()) dirtyState.delete(); 
        }

        File keyStoreFile = new File("all_members.p12");
        secrets = new KeyStoreManager(keyStoreFile, "changeit".toCharArray());

        KeyPair client10Keys = new KeyPair(secrets.getPublicKey("client10_pub"), secrets.getPrivateKey("client10_priv"));
        KeyPair client11Keys = new KeyPair(secrets.getPublicKey("client11_pub"), secrets.getPrivateKey("client11_priv"));

        client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());
        client11 = CryptoUtils.generateAddressFromPubKey(client11Keys.getPublic());

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
    public void testInsufficientDepCoin() {
        ClientLibrary client10CL = null;
        try {
            Logger.test(null, "--- Running Insufficient DepCoin Test ---");

            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            long initialClient10DEP = stateManager.getBalances(client10).get("DEP");
            Logger.test(null, "Initial Client 10 DEP: " + initialClient10DEP);

            // setup Client 10's Client Library
            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            client10CL.start();
            client10CL.send("balance");

            Thread.sleep(500); 

            // create a transaction that Client 10 can't afford
            long transferAmount = 1000;
            long gasLimit = initialClient10DEP + 50000; // massive gas limit
            long gasPrice = 10;
            
            String cmd = String.format("transferDEP %s %d %d %d", client11.toHexString(), transferAmount, gasLimit, gasPrice);
            Logger.test(null, "Client 10 executing unaffordable transaction: " + cmd);
            client10CL.send(cmd);

            // wait a few seconds to ensure the network drops it.
            Logger.test(null, "Waiting 5 seconds to verify transaction is dropped...");
            Thread.sleep(5000); 

            stateManager.loadWorld(); 
            long finalClient10DEP = stateManager.getBalances(client10).get("DEP");
            
            Logger.test(null, "Final Client 10 DEP: " + finalClient10DEP);

            // because the transaction was invalid (not enough funds for max gas)
            // it should be rejected before consensus (no gas charged)
            assertEquals(initialClient10DEP, finalClient10DEP, "SECURITY FAILURE: Client was charged gas for a structurally invalid transaction");
            Logger.test(null, "Transaction correctly dropped before consensus");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
        }
    }

    @Test
    public void testInsufficientISTCoin() {
        ClientLibrary client10CL = null;
        try {
            Logger.test(null, "--- Running Insufficient ISTCoin Test ---");

            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            long initialClient10IST = stateManager.getBalances(client10).get("IST");
            long initialClient11IST = stateManager.getBalances(client11).get("IST");
            long initialClient10DEP = stateManager.getBalances(client10).get("DEP");

            Logger.test(null, "Initial Client 10 IST: " + initialClient10IST);
            Logger.test(null, "Initial Client 10 DEP: " + initialClient10DEP);

            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            client10CL.start();
            client10CL.send("balance");

            Thread.sleep(500);

            // create a Smart Contract transaction that Client 10 can afford the gas for, 
            // but doesn't have the IST Coins for it
            long transferAmount = initialClient10IST + 5000; // guaranteed to be higher than what they own
            long gasLimit = 450000;
            long gasPrice = 10;
            
            String cmd = String.format("transferIST %s %d %d %d", client11.toHexString(), transferAmount, gasLimit, gasPrice);
            Logger.test(null, "Client 10 executing reverting transaction: " + cmd);
            client10CL.send(cmd);

            Logger.test(null, "Waiting 5 seconds for Block consensus and EVM execution...");
            Thread.sleep(5000); 

            stateManager.loadWorld(); 
            long finalClient10IST = stateManager.getBalances(client10).get("IST");
            long finalClient11IST = stateManager.getBalances(client11).get("IST");
            long finalClient10DEP = stateManager.getBalances(client10).get("DEP");
            
            Logger.test(null, "Final Client 10 IST: " + finalClient10IST);
            Logger.test(null, "Final Client 10 DEP: " + finalClient10DEP);

            // the EVM must revert the state and no IST Coins should be transfered
            assertEquals(initialClient10IST, finalClient10IST, "SECURITY FAILURE: IST Coins were moved despite insufficient balance");
            assertEquals(initialClient11IST, finalClient11IST, "SECURITY FAILURE: Client 11 received ISTCoin from a reverted transaction");
            
            // the client should still pay the gas used by the failed EVM execution
            assertTrue(finalClient10DEP < initialClient10DEP, "SECURITY FAILURE: Client 10 was not charged DepCoin gas for the reverted EVM execution");

            Logger.test(null, "EVM properly reverted the transaction and charged the gas");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
        }
    }
} 
