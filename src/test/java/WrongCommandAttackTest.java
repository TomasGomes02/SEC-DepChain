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

public class WrongCommandAttackTest {

    private KeyStoreManager secrets;
    private List<Thread> memberThreads = new ArrayList<>();
    private List<BlockchainConsensus> activeServers = new ArrayList<>();

    @BeforeEach
    public void setup() throws Exception {
        Logger.test(null, "--- Setting up test: WRONG_COMMAND Attack ---");

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
            ByzantineBehaviour behaviour = (i == 1) ? ByzantineBehaviour.WRONG_COMMAND : ByzantineBehaviour.NONE;
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
    public void testWrongCommandAttack() throws Exception {
        KeyPair client10Keys = new KeyPair(secrets.getPublicKey("client10_pub"), secrets.getPrivateKey("client10_priv"));
        Address client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());

        Transaction tx = new Transaction(null, client10, client10, 10, 1, 450000, 1, null, client10Keys.getPublic());
        tx.setSignature(CryptoUtils.sign(client10Keys.getPrivate(), tx.getId().getBytes()));
        for (BlockchainConsensus member : activeServers) {
            member.getState().pendingTransactions.add(tx);
        }


        activeServers.get(0).setReadyToPropose(true);

        Logger.test(null, "Waiting for network to handle the empty block and Node 2 to process the mempool...");

        long deadline = System.currentTimeMillis() + 50_000;
        boolean passed = false;
        BlockchainConsensus honestNode2 = activeServers.get(1);

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            // once Node 2 processes the transactions, the mempool will be empty
            if (honestNode2.getState().pendingTransactions.isEmpty() && honestNode2.getState().getBlockchain().size() > 1) {
                Logger.test(null, "PASS: Node 2 successfully cleared the mempool in View " + honestNode2.getState().getCurrView());
                passed = true;
                break;
            }
        }
        assertTrue(passed, "The network stalled and failed to process the remaining transactions");
    }
}