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

public class ByzantineNoResponseTest {

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
            
            // make node 1 (the leader) byzantine   (use either 1 or 4 to test leader vs member)
            ByzantineBehaviour behaviour = (i == 1) ? ByzantineBehaviour.NO_RESPONSE : ByzantineBehaviour.NONE;
            
            BlockchainConsensus server = new BlockchainConsensus(i, addr, new DatagramSocket(8000 + i), secrets, behaviour);
            activeServers.add(server);
        }

        for (BlockchainConsensus member : activeServers) {
            for (BlockchainConsensus memberToAdd : activeServers) {
                member.getState().addMember(memberToAdd);
            }
            for (int clientId = 10; clientId < 14; clientId++) {
                member.addClientConnection(clientId, new InetSocketAddress("localhost", 5000 + clientId));
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
    public void testNoResponseLeaderTriggersViewChange() {
        ClientLibrary client10CL = null;
        try {
            Logger.test(null, "--- Running Byzantine Leader Test ---");

            BlockchainStateManager stateManager = new BlockchainStateManager("src/main/java/contracts/state1.json", "src/main/java/contracts/genesis.json");
            stateManager.loadWorld();

            long initialClient10DEP = stateManager.getBalances(client10).get("DEP");
            long initialClient11DEP = stateManager.getBalances(client11).get("DEP");

            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            client10CL.start();
            client10CL.send("balance");
            Thread.sleep(500); 

            long transferAmount = 1000;
            long gasLimit = 450000;
            long gasPrice = 10;

            String cmd1 = String.format("transferDEP %s %d %d %d", client11.toHexString(), transferAmount, gasLimit, gasPrice);
            Logger.test(null, "Client 10 executing transaction: " + cmd1);
            client10CL.send(cmd1);
            Thread.sleep(500);

            // wait for honest nodes to timeout, send NEW_VIEW, and let node 2 propose
            Logger.test(null, "Waiting 35 seconds for view change and consensus...");
            Thread.sleep(35000); 

            // read from node 2's state
            BlockchainStateManager state2 = new BlockchainStateManager("src/main/java/contracts/state2.json", "src/main/java/contracts/genesis.json");
            state2.loadWorld(); 
            long finalClient10DEP = state2.getBalances(client10).get("DEP");
            long finalClient11DEP = state2.getBalances(client11).get("DEP");

            long expectedReceiverBalance = initialClient11DEP + transferAmount;
            assertEquals(expectedReceiverBalance, finalClient11DEP, "BFT FAILURE: The honest nodes failed to reach consensus after the Leader stopped responding");
            assertTrue(finalClient10DEP < initialClient10DEP, "Sender wasn't charged");
            
            Logger.test(null, "Honest replicas successfully bypassed the NO_RESPONSE leader via View Change");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
        }
    }
}
