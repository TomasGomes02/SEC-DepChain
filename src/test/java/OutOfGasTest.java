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

public class OutOfGasTest {

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
    public void testOutOfGasExecution() {
        ClientLibrary client10CL = null;
        try {
            Logger.test(null, "--- Running Out of Gas EVM Execution Test ---");

            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld();

            long initialClient10IST = stateManager.getBalances(client10).get("IST");
            long initialClient10DEP = stateManager.getBalances(client10).get("DEP");
            long initialClient11IST = stateManager.getBalances(client11).get("IST");

            Logger.test(null, "Initial Client 10 IST: " + initialClient10IST);
            Logger.test(null, "Initial Client 10 DEP: " + initialClient10DEP);
            Logger.test(null, "Initial Client 11 IST: " + initialClient11IST);

            // setup Client 10
            DatagramSocket client10Socket = new DatagramSocket(5010);
            client10CL = new ClientLibrary(10, new InetSocketAddress("localhost", 5010), client10Socket, secrets);
            client10CL.start();
            client10CL.send("balance");

            Thread.sleep(500);

            // out of gas transaction
            long transferAmount = 50; 
            long gasLimit = 22000;  // too little gas for the EVM
            long gasPrice = 10;
            
            String cmd1 = String.format("transferIST %s %d %d %d", client11.toHexString(), transferAmount, gasLimit, gasPrice);
            Logger.test(null, "Client 10 executing out of gas transaction: " + cmd1);
            client10CL.send(cmd1);

            // dummy transaction just to force consensus
            long normalGasLimit = 450000;
            String cmd2 = String.format("transferDEP %s 1 %d %d", client11.toHexString(), normalGasLimit, gasPrice);
            System.out.println("Client 10 executing dummy transaction: " + cmd2);
            client10CL.send(cmd2);

            Logger.test(null, "Waiting 5 seconds for Block consensus...");
            Thread.sleep(5000); 

            // verify state
            stateManager.loadWorld(); 
            long finalClient10IST = stateManager.getBalances(client10).get("IST");
            long finalClient10DEP = stateManager.getBalances(client10).get("DEP");
            long finalClient11IST = stateManager.getBalances(client11).get("IST");

            Logger.test(null, "Final Client 10 IST: " + finalClient10IST);
            Logger.test(null, "Final Client 10 DEP: " + finalClient10DEP);
            Logger.test(null, "Final Client 11 IST: " + finalClient10IST);


            // the EVM aborted, so no ISTCoin transfered
            assertEquals(initialClient10IST, finalClient10IST, "SECURITY FAILURE: ISTCoin moved despite running out of gas");
            assertEquals(initialClient11IST, finalClient11IST, "SECURITY FAILURE: Client 11 received ISTCoin from an aborted transaction");

            // the user must be charged for the maximum gas they authorized (gas_limit * gas_price)
            long expectedGasPenalty = gasLimit * gasPrice;
            
            //  Client 10's DEP balance needs to have subtracted the expected penalty
            assertTrue(finalClient10DEP <= (initialClient10DEP - expectedGasPenalty), 
                "SECURITY FAILURE: Client 10 was not charged the Out of Gas penalty. Expected drop of at least " + expectedGasPenalty);

            Logger.test(null, "Transaction correctly ran Out of Gas, reverted IST, and charged the maximum gas penalty");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        } finally {
            if (client10CL != null) client10CL.stop();
        }
    }
}
