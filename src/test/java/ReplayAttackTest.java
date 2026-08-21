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

public class ReplayAttackTest {

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
    public void testReplayAttackDropsDuplicateTransaction() {
        ClientLibrary client10CL = null;
        try {
            Logger.test(null, "--- Running Replay Attack Test ---");

            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            long initialClient10DEP = stateManager.getBalances(client10).get("DEP");
            long initialClient11DEP = stateManager.getBalances(client11).get("DEP");

            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            
            // set byzantine behaviour (prevents the client from incrementing its nonce)
            client10CL.setByzantineBehaviour("Replay");
    
            client10CL.start();
            client10CL.send("balance");

            Thread.sleep(500); 

            long transferAmount = 1000;
            long gasLimit = 450000;
            long gasPrice = 10;

            String cmd = String.format("transferDEP %s %d %d %d", client11.toHexString(), transferAmount, gasLimit, gasPrice);

            // send the first valid transaction
            Logger.test(null, "Client 10 executing first (valid) transaction: " + cmd);
            client10CL.send(cmd);
            Thread.sleep(500);

            // send the same transaction again (replay attack)
            // because behaviour is "Replay", the nonce is still the same as the first transaction
            Logger.test(null, "Client 10 executing second (replay) transaction: " + cmd);
            client10CL.send(cmd);

            Logger.test(null, "Waiting 5 seconds to verify Replay is dropped...");
            Thread.sleep(5000);   // if gasLimit isnt enough to trigger the block proposal (for example, 50000 < 80% 500000)
                                  // then increase this timer to 30000 to give time for the timeout to trigger the proposal

            stateManager.loadWorld(); 
            long finalClient10DEP = stateManager.getBalances(client10).get("DEP");
            long finalClient11DEP = stateManager.getBalances(client11).get("DEP");

            // Client 11 should only receive the amount from the first transaction
            long expectedReceiverBalance = initialClient11DEP + transferAmount;
            assertEquals(expectedReceiverBalance, finalClient11DEP, "SECURITY FAILURE: Replay attack succeeded. Funds were transferred twice");

            // the sender should only be charged for the first block. The replayed transaction should cost 0
            assertTrue(finalClient10DEP < initialClient10DEP, "Sender wasn't charged for the first block");
            
            Logger.test(null, "Replay Attack successfully denied by the server's nonce validation");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
        }
    }
}