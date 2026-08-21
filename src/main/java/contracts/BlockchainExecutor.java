package contracts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import logger.Logger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import transactions.Transaction;
import utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockchainExecutor {

    private final SimpleWorld world;
    private final ByteArrayOutputStream baos;
    private final EVMExecutor executor;
    private final Path ISTCOIN_EVMBC_PATH = Path.of("src/main/java/contracts/ISTcoin.bytecode");
    private byte[] ISTCoinContractEVMBC;


    public BlockchainExecutor(SimpleWorld world) {
        this.world = world;
        this.baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        StandardJsonTracer tracer =
                new StandardJsonTracer(ps, true, true, true, true);

        this.executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        this.executor.tracer(tracer);
        this.executor.worldUpdater(world.updater());
        this.ISTCoinContractEVMBC = fetchISTCoinContractEVMBC();
    }

    public Map<String, Long> executeBlock(List<Transaction> transactions) {
        Map<String, Long> transactionFees = new HashMap<>();
        for (Transaction t : transactions) {
            long fee = executeTransaction(t);
            transactionFees.put(t.getId(), fee);
        }
        return transactionFees;
    }

    // Single transaction execution

    private long executeTransaction(Transaction transaction) {
        MutableAccount senderAccount = (MutableAccount) world.get(transaction.getSender());

        if (senderAccount == null) {
            Logger.warn(null, "[BlockExecutor] Sender account not found: " + transaction.getSender());
            return 0;
        }

        long actualFee = 0;

        // handle native DepCoin transfer
        if (transaction.getAmount() > 0 && transaction.getReceiver() != null) {
            MutableAccount receiverAccount = (MutableAccount) world.get(transaction.getReceiver());
            if (receiverAccount == null) {
                Logger.warn(null, "[BlockExecutor] Receiver account not found: " + transaction.getReceiver());
                return 0;
            }
            long senderBalance = senderAccount.getBalance().toLong();
            if (senderBalance < transaction.getAmount()) {
                Logger.warn(null, "[BlockExecutor] Insufficient balance for native transfer.");
                return 0;
            }
            senderAccount.decrementBalance(Wei.of(transaction.getAmount()));
            receiverAccount.incrementBalance(Wei.of(transaction.getAmount()));
        }

        // execute payload if present
        if (transaction.getPayload() != null) {
            WorldUpdater sandboxUpdater = world.updater();

            executor.sender(transaction.getSender());
            executor.receiver(transaction.getReceiver());
            executor.gas(transaction.getGasLimit());
            executor.gasPriceGWei(Wei.of(transaction.getGasPrice()));
            executor.code(Bytes.wrap(getISTCoinContractEVMBC()));
            executor.callData(Bytes.wrap(Bytes.fromHexString(transaction.getPayload())));
            executor.worldUpdater(sandboxUpdater);

            Address contractAddr = transaction.getReceiver();
            String senderSlot = Utils.mappingSlot(transaction.getSender().toHexString(), 3);

            MutableAccount contractBefore = (MutableAccount) world.get(contractAddr);
            if (contractBefore != null) {
                UInt256 balBefore = contractBefore.getStorageValue(UInt256.fromHexString(senderSlot));
                Logger.debug(null, "Sender IST before: " + (balBefore != null ? balBefore.toLong() : "NULL"));
            } 

            /* baos.reset();
            executor.execute();*/

            baos.reset();
            executor.execute();

            MutableAccount contractAfter = (MutableAccount) world.get(contractAddr);
            if (contractAfter != null) {
                UInt256 balAfter = contractAfter.getStorageValue(UInt256.fromHexString(senderSlot));
                Logger.debug(null, "Sender IST after: " + (balAfter != null ? balAfter.toLong() : "NULL"));
            }

            long gasUsed = extractGasUsed(baos);
            long fee = transaction.calculateFee(gasUsed);
            String output = baos.toString();
            boolean evmError = output.contains("\"error\":") || output.contains("revert");
            boolean outOfGas = transaction.isAborted(gasUsed);

            /* // DEBUG: Check storage after (must commit first to read updated values)
            executor.commitWorldState(); */


            /* long gasUsed = extractGasUsed(baos);
            long fee = transaction.calculateFee(gasUsed);
            String output = baos.toString();
            boolean evmError = output.contains("\"error\":") || output.contains("revert");
            
            boolean outOfGas = transaction.isAborted(gasUsed); */

            if (!outOfGas && !evmError) {
                sandboxUpdater.commit();
                Logger.info(null, "[BlockExecutor] Transaction executed successfully. Fee charged: " + fee);
            } else if (outOfGas) {
                Logger.warn(null, "[BlockExecutor] Transaction aborted (Out of Gas). Gas Used: " 
                        + gasUsed + " > Limit: " + transaction.getGasLimit() + ". Fee charged: " + fee);
            } else {
                Logger.warn(null, "[BlockExecutor] Transaction reverted by Smart Contract. Fee charged: " + fee);
            }

            // deduct fee from sender's DepCoin balance regardless of success
            long balance = senderAccount.getBalance().toLong();
            actualFee = Math.min(fee, balance); // never go below zero
            senderAccount.decrementBalance(Wei.of(actualFee));

        } else {
            // pure DepCoin transfer, no EVM execution, charge a flat fee
            long fee = transaction.calculateFee(21_000);
            long balance = senderAccount.getBalance().toLong();
            actualFee = Math.min(fee, balance);
            senderAccount.decrementBalance(Wei.of(actualFee));
            Logger.info(null, "[BlockExecutor] Native transfer executed."
                    + " Fee charged: " + actualFee);
        }

        // always increment nonce after execution
        senderAccount.incrementNonce();

        return actualFee;
    }

    private long extractGasUsed(ByteArrayOutputStream baos) {
        long intrinsicGas = 21000;

        String[] lines = baos.toString().split("\\r?\\n");
        if (lines.length < 2) return intrinsicGas;
        try {
            JsonObject first = JsonParser.parseString(lines[0]).getAsJsonObject();
            JsonObject last  = JsonParser.parseString(
                    lines[lines.length - 1]).getAsJsonObject();
            long gasStart = first.get("gas").getAsLong();
            long gasEnd   = last.get("gas").getAsLong();
            return gasStart - gasEnd + intrinsicGas;
        } catch (Exception e) {
            Logger.error(null, "[BlockExecutor] Failed to extract gasUsed: " + e.getMessage());
            return intrinsicGas;
        }
    }

    private byte[] getISTCoinContractEVMBC() {
        return ISTCoinContractEVMBC;
    }

    private byte[] fetchISTCoinContractEVMBC() {
    if (!Files.exists(ISTCOIN_EVMBC_PATH)) {
        Logger.error(null, "ISTCoin Bytecode not found at path: " + ISTCOIN_EVMBC_PATH);
        return new byte[0];
    }

    try {
        String hexString = Files.readString(ISTCOIN_EVMBC_PATH).trim();
        
        if (!hexString.startsWith("0x"))
            hexString = "0x" + hexString;

        return Bytes.fromHexString(hexString).toArray();

    } catch (IOException e) {
        Logger.error(null, "Failed to read the bytecode file: " + e.getMessage());
        return new byte[0];
    }
}



}
