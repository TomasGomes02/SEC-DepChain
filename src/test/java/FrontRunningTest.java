import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import consensus.BlockchainConsensus;
import consensus.ByzantineBehaviour;
import logger.Logger;
import secrets.KeyStoreManager;
import transactions.Transaction;
import utils.CryptoUtils;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

public class FrontRunningTest {

    private static KeyStoreManager secrets;
    private static List<Thread> memberThreads = new ArrayList<>();
    private static List<BlockchainConsensus> activeServers = new ArrayList<>();

    @BeforeEach
    public void setup() throws Exception {
        Logger.test(null, "--- Setting up Byzantine Leader Frontrunning Test ---");

        for (int i = 1; i <= 4; i++) {
            File dirtyState = new File("src/main/java/contracts/state" + i + ".json");
            if (dirtyState.exists()) dirtyState.delete(); 
        }

        File keyStoreFile = new File("all_members.p12");
        secrets = new KeyStoreManager(keyStoreFile, "changeit".toCharArray());

        activeServers.clear();
        memberThreads.clear();

        for (int i = 1; i <= 4; i++) {
            InetSocketAddress addr = new InetSocketAddress("localhost", 8000 + i);

            // Node 1 is malicious (BAD_SORTING). Nodes 2, 3, 4 are honest.
            ByzantineBehaviour behaviour = (i == 1) ? ByzantineBehaviour.BAD_SORTING : ByzantineBehaviour.NONE;

            BlockchainConsensus server = new BlockchainConsensus(i, addr, new DatagramSocket(8000 + i), secrets, behaviour);
            activeServers.add(server);
        }

        for (BlockchainConsensus member : activeServers) {
            for (BlockchainConsensus memberToAdd : activeServers) {
                member.getState().addMember(memberToAdd);
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
    public void testByzantineLeaderSorting() throws Exception {
        try {
            // generate two transactions with different gas prices
            KeyPair client10Keys = new KeyPair(secrets.getPublicKey("client10_pub"), secrets.getPrivateKey("client10_priv"));
            Address client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());

            Transaction lowFeeTx = new Transaction(null, client10, client10, 0, 1, 100000, 1, null, client10Keys.getPublic());
            lowFeeTx.setSignature(CryptoUtils.sign(client10Keys.getPrivate(), lowFeeTx.getId().getBytes()));

            Transaction highFeeTx = new Transaction(null, client10, client10, 0, 1, 300000, 2, null, client10Keys.getPublic());
            highFeeTx.setSignature(CryptoUtils.sign(client10Keys.getPrivate(), highFeeTx.getId().getBytes()));

            for (BlockchainConsensus member : activeServers) {
                member.getState().pendingTransactions.add(lowFeeTx);
                member.getState().pendingTransactions.add(highFeeTx);
            }

            activeServers.get(0).setReadyToPropose(true);

            Logger.test(null, "Waiting for Node 1 to be overthrown and Node 2 to take over...");

            long deadline = System.currentTimeMillis() + 30_000;
            boolean passed = false;

            BlockchainConsensus honestNode2 = activeServers.get(1);

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500);

                if (honestNode2.getState().getBlockchain().size() > 1) {
                    int commitView = honestNode2.getState().getCurrView();
                    Logger.test(null, "PASS: Node 2 successfully committed the block in View " + commitView + "!");
                    passed = true;
                    break;
                }
            }

            assertTrue(passed, "The network failed to overthrow the leader and commit the block");

        } catch (Exception e) {
            fail("Exception thrown: " + e.getMessage());
        }
    }
}