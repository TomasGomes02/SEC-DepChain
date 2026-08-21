import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import logger.Logger;

import static org.junit.jupiter.api.Assertions.*;
import secrets.KeyStoreManager;
import transactions.Transaction;
import utils.CryptoUtils;

import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TransactionSortTest {

    @Test
    public void testTransactionSorting() throws Exception {
        Logger.test(null, "--- Testing EVM Transaction Sorting Rules ---");

        File keyStoreFile = new File("all_members.p12");
        KeyStoreManager secrets = new KeyStoreManager(keyStoreFile, "changeit".toCharArray());

        KeyPair client10Keys = new KeyPair(
                secrets.getPublicKey("client10_pub"),
                secrets.getPrivateKey("client10_priv")
        );
        KeyPair client11Keys = new KeyPair(
                secrets.getPublicKey("client11_pub"),
                secrets.getPrivateKey("client11_priv")
        );
        Address client10 = CryptoUtils.generateAddressFromPubKey(client10Keys.getPublic());
        Address client11 = CryptoUtils.generateAddressFromPubKey(client11Keys.getPublic());

        Logger.test(null, "Client 10: " + client10.toHexString());
        Logger.test(null, "Client 11: " + client11.toHexString() + "\n");

        // create a scrambled list of transactions
        List<Transaction> pool = new ArrayList<>();

        // Client 11 pays a fee of 50 so he should always be #1.
        pool.add(createTx(client11, client11Keys, 50, 1));

        // Client 10 sends 3 transactions with the same fee of 10, but out of order nonces.
        pool.add(createTx(client10, client10Keys, 10, 3));
        pool.add(createTx(client10, client10Keys, 10, 1));
        pool.add(createTx(client10, client10Keys, 10, 2));

        Logger.test(null, "Before Sorting:");
        for (Transaction t : pool) {
            Logger.test(null, String.format("Sender: %s | Fee: %2d | Nonce: %d",
                    t.getSenderAsString().substring(0, 10) + "...",
                    t.getGasPrice(),
                    t.getNonce()));
        }

        // sort them
        Collections.sort(pool);

        Logger.test(null, "After Sorting (Deterministic Block Order):");
        for (Transaction t : pool) {
            Logger.test(null, String.format("Sender: %s | Fee: %2d | Nonce: %d",
                    t.getSenderAsString().substring(0, 10) + "...",
                    t.getGasPrice(),
                    t.getNonce()));
        }

        // verify if it worked
        boolean passed =
                pool.get(0).getSenderAsString().equals(client11.toHexString()) &&
                        pool.get(1).getNonce() == 1 &&
                        pool.get(2).getNonce() == 2 &&
                        pool.get(3).getNonce() == 3;

        if (passed) {
            Logger.test(null, "PASS: Transactions perfectly sorted by Fee -> Sender -> Nonce");
        } else {
            Logger.test(null, "FAIL: Sorting logic is broken");
        }

        assertTrue(passed, "FAIL: Sorting logic is broken. Transactions were not ordered correctly.");
    }

    private Transaction createTx(Address sender, KeyPair keys, long fee, int nonce) {
        Transaction tx = new Transaction(
                null, sender, sender, 0, fee, 1, nonce, null, keys.getPublic());
        tx.setSignature(CryptoUtils.sign(keys.getPrivate(), tx.getId().getBytes()));
        return tx;
    }
}