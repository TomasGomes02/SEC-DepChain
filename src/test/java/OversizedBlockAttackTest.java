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

public class OversizedBlockAttackTest {

    private KeyStoreManager secrets;
    private List<Thread> memberThreads = new ArrayList<>();
    private List<BlockchainConsensus> activeServers = new ArrayList<>();

    @BeforeEach
    public void setup() throws Exception {
        for (int i = 1; i <= 4; i++) {
            File dirtyState = new File("src/main/java/contracts/state" + i + ".json");
            if (dirtyState.exists()) dirtyState.delete(); 
        }

        Logger.test(null, "--- Booting Network: OVERSIZED_BLOCK Attack ---");

        File keyStoreFile = new File("all_members.p12");
        secrets = new KeyStoreManager(keyStoreFile, "changeit".toCharArray());

        activeServers.clear();
        memberThreads.clear();

        for (int i = 1; i <= 4; i++) {
            InetSocketAddress addr = new InetSocketAddress("localhost", 8000 + i);
            ByzantineBehaviour behaviour = (i == 1) ? ByzantineBehaviour.OVERSIZED_BLOCK : ByzantineBehaviour.NONE;
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
    public void testOversizedBlockAttack() throws Exception {
        KeyPair client10Keys = new KeyPair(secrets.getPublicKey("client10_pub"), secrets.getPrivateKey("client10_priv"));
        Address client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());

        // Inject 11 transactions of 50k Gas (Total: 550,000 Gas). MAX_BLOCK_GAS is 80% of 500,000.
        for (int i = 1; i <= 11; i++) {
            Transaction tx = new Transaction(null, client10, client10, 0, 10, 50000, i, null, client10Keys.getPublic());
            tx.setSignature(CryptoUtils.sign(client10Keys.getPrivate(), tx.getId().getBytes()));
            for (BlockchainConsensus member : activeServers) {
                member.getState().pendingTransactions.add(tx);
            }
        }

        activeServers.get(0).setReadyToPropose(true);

        Logger.test(null, "Waiting for network to reject oversized block and Node 2 to secure the network...");

        long deadline = System.currentTimeMillis() + 30_000;
        boolean passed = false;
        BlockchainConsensus honestNode2 = activeServers.get(1);

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            // Node 2 should only pack 10 transactions, leaving exactly 1 in the mempool
            if (honestNode2.getState().pendingTransactions.size() == 1) {
                Logger.test(null, "PASS: Node 2 successfully packed exactly 10 transactions in View " + honestNode2.getState().getCurrView());
                passed = true;
                break;
            }
        }
        assertTrue(passed, "The network failed to overthrow the attacker or pack the correct block size!");
    }
}