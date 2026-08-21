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

public class PersistenceRecoveryTest {

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

        // give members time to parse state and establish peer handshakes
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
    public void testMemberCrashAndRecovery() {
        ClientLibrary client10CL = null;
        try {
            Logger.test(null, "--- Running Persistence and Restart Recovery Test ---");

            // setup Client 10's Client Library
            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            client10CL.start();
            client10CL.send("balance");

            Thread.sleep(500); // wait for handshakes

            long gasLimit = 450000;
            long gasPrice = 10;

            Logger.test(null, "--- Sending Transactions to modify state ---");
            
            // Client 10 sends 1000 DEP to Client 11
            String tx1 = String.format("transferDEP %s 1000 %d %d", client11.toHexString(), gasLimit, gasPrice);
            client10CL.send(tx1);
            Thread.sleep(500);

            // wait for consensus
            Logger.test(null, "Waiting 5 seconds for consensus...");
            Thread.sleep(5000); 

            // get the state from Member 1
            BlockchainStateManager state1 = new BlockchainStateManager("src/main/java/contracts/state1.json", "src/main/java/contracts/genesis.json");
            state1.loadWorld();
            long member1Client10Dep = state1.getBalances(client10).get("DEP");
            long member1Client11Dep = state1.getBalances(client11).get("DEP");

            // crash member 4
            Logger.test(null, "--- Simulating Member 4 Crash ---");
            BlockchainConsensus member4 = activeServers.get(3);
            Thread member4Thread = memberThreads.get(3);
            
            member4.stop(); // kill the socket
            member4Thread.interrupt(); // kill the thread
            
            Thread.sleep(500);
            Logger.test(null, "Member 4 is offline.");

            Logger.test(null, "--- Restarting Member 4 ---");
            
            InetSocketAddress addr4 = new InetSocketAddress("localhost", 8004);
            BlockchainConsensus recoveredMember4 = new BlockchainConsensus(4, addr4, new DatagramSocket(8004), secrets, ByzantineBehaviour.NONE);
            
            // wire it to the existing network
            for (BlockchainConsensus memberToAdd : activeServers) {
                recoveredMember4.getState().addMember(memberToAdd);
            }
            
            for (int clientId = 10; clientId < 14; clientId++) {
                int clientPort = 5000 + clientId;
                recoveredMember4.addClientConnection(clientId, new InetSocketAddress("localhost", clientPort));
            }
            recoveredMember4.createConnections();
            
            Thread recoveredMember4Thread = new Thread(recoveredMember4);
            recoveredMember4Thread.start();
            
            // replace the crashed member in our test lists so @AfterEach cleans up properly
            activeServers.set(3, recoveredMember4);
            memberThreads.set(3, recoveredMember4Thread);

            // wait for it to boot, read the disk, and finish its handshakes
            Thread.sleep(500); 

            // read the state to prove the member loaded it successfully during its constructor
            BlockchainStateManager state4 = recoveredMember4.getState().getStateManager();

            long recoveredClient10Dep = state4.getBalances(client10).get("DEP");
            long recoveredClient11Dep = state4.getBalances(client11).get("DEP");

            Logger.test(null, "Member 1 (Online) Client 10 DEP:    " + member1Client10Dep);
            Logger.test(null, "Member 4 (Recovered) Client 10 DEP: " + recoveredClient10Dep);

            assertEquals(member1Client10Dep, recoveredClient10Dep, "RECOVERY FAILED: Member 4's client10 balance does not match the network");
            assertEquals(member1Client11Dep, recoveredClient11Dep, "RECOVERY FAILED: Member 4's client11 balance does not match the network");
            
            Logger.test(null, "Member 4 crashed, rebooted, rejoined the network, and successfully recovered its World State");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
        }
    }
}