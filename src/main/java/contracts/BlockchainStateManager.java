package contracts;

import com.google.gson.*;

import logger.Logger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import transactions.Transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import utils.Utils;

public class BlockchainStateManager {

    private final Path stateFile;
    private final Path genesisFile;
    private final Gson gson;
    private JsonObject blockchainData;
    private final Map<String, Map<String, String>> contractStorage = new HashMap<>();
    private final Set<String> addresses = new HashSet<>();

    public BlockchainStateManager(String stateFilePath, String genesisFilePath) {
        this.stateFile = Path.of(stateFilePath);
        this.genesisFile = Path.of(genesisFilePath);
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    public SimpleWorld loadWorld() throws IOException {
        if (!Files.exists(stateFile)) {
            Logger.info(null, "[Storage] No state file found. Loading from genesis");
            if (!Files.exists(genesisFile)) {
                throw new IOException("Genesis file missing at: " + genesisFile);
            }
            blockchainData = JsonParser.parseString(Files.readString(genesisFile)).getAsJsonObject();
            saveToFile();
        } else {
            Logger.info(null, "[Storage] Loading existing state from: " + stateFile);
            blockchainData = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
        }

        int latestBlock = getLatestBlockNumber();
        Logger.debug(null, "[Storage] Rebuilding SimpleWorld from block " + latestBlock);
        return buildWorldFromBlock(latestBlock);
    }

    private SimpleWorld buildWorldFromBlock(int blockNumber) {
        SimpleWorld world = new SimpleWorld();
        JsonObject block = blockchainData.getAsJsonObject(String.valueOf(blockNumber));
        JsonObject state = block.getAsJsonObject("state");

        for (Map.Entry<String, JsonElement> entry : state.entrySet()) {
            addresses.add(entry.getKey());
            Address addr = Address.fromHexString(entry.getKey());
            JsonObject accountData = entry.getValue().getAsJsonObject();

            long balance = accountData.get("balance").getAsLong();
            int nonce = accountData.get("nonce").getAsInt();
            String type = accountData.get("type").getAsString();

            world.createAccount(addr, nonce, Wei.of(balance));
            MutableAccount account = (MutableAccount) world.get(addr);

            if ("CONTRACT".equals(type)) {
                account.setCode(Bytes.fromHexString(accountData.get("code").getAsString()));
                JsonObject storage = accountData.getAsJsonObject("storage");

                Map<String, String> currentStorageMap = new HashMap<>();

                for (Map.Entry<String, JsonElement> slot : storage.entrySet()) {
                    account.setStorageValue(
                            UInt256.fromHexString(slot.getKey()),
                            UInt256.fromHexString(slot.getValue().getAsString())
                    );
                    currentStorageMap.put(slot.getKey(), slot.getValue().getAsString());
                }
                contractStorage.put(addr.toHexString(), currentStorageMap);
            }
        }
        return world;
    }

    public void setContractStorage(String contractAddress, Map<String, String> storage) {
        this.contractStorage.put(contractAddress, storage);
    }

    public void updateContractStorageSlot(String contractAddress, String slot, String value) {
        this.contractStorage.computeIfAbsent(contractAddress, k -> new HashMap<>()).put(slot, value);
    }

    public void appendBlock(
            SimpleWorld world,
            List<Transaction> transactions
    ) throws IOException {
        int nextBlockNumber = getLatestBlockNumber() + 1;
        JsonObject newBlock = new JsonObject();
        newBlock.add("transactions", transactionsToJson(transactions));

        JsonObject stateObj = new JsonObject();

        for (String hexAddress : addresses) {
            Address addr = Address.fromHexString(hexAddress);
            Account account = world.get(addr);

            if (account != null) {
                boolean isContract = account.getCode() != null && !account.getCode().isEmpty();
                String code = isContract ? account.getCode().toHexString() : null;
                Map<String, String> storage = null;
                if (isContract) {
                    storage = new HashMap<>();
                    Map<String, String> cachedSlots = this.contractStorage.get(hexAddress);
                    if (cachedSlots != null) {
                        for (String slotKey : cachedSlots.keySet()) {
                            UInt256 value = account.getStorageValue(UInt256.fromHexString(slotKey));
                            storage.put(slotKey, value != null ? value.toHexString() : cachedSlots.get(slotKey));
                        }
                    }
                }

                stateObj.add(hexAddress, serializeAccount(account, isContract, code, storage));
            }
        }

        newBlock.add("state", stateObj);

        String previousBlockHash = getBlockHash(nextBlockNumber - 1);
        if (previousBlockHash != null) {
            newBlock.addProperty("previous_block_hash", previousBlockHash);
        } else {
            newBlock.add("previous_block_hash", JsonNull.INSTANCE);
        }

        newBlock.addProperty("block_hash", sha256Hex(gson.toJson(stateObj)));

        blockchainData.add(String.valueOf(nextBlockNumber), newBlock);
        saveToFile();

        Logger.info(null, "[Storage] Block " + nextBlockNumber + " committed to Blockchain");
    }

    private JsonObject serializeAccount(
            Account account,
            boolean isContract,
            String code,
            Map<String, String> storageMap
    ) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", isContract ? "CONTRACT" : "EOA");
        obj.addProperty("balance", account.getBalance().toLong());
        obj.addProperty("nonce", account.getNonce());

        if (isContract) {
            obj.addProperty("code", code);
            JsonObject storageJson = new JsonObject();
            if (storageMap != null) {
                for (Map.Entry<String, String> entry : storageMap.entrySet()) {
                    storageJson.addProperty(entry.getKey(), entry.getValue());
                }
            }
            obj.add("storage", storageJson);
        }
        return obj;
    }

    private void saveToFile() throws IOException {
        Files.writeString(stateFile, gson.toJson(blockchainData));
    }

    public int getLatestBlockNumber() {
        int max = 0;
        if (blockchainData != null) {
            for (String key : blockchainData.keySet()) {
                try {
                    int n = Integer.parseInt(key);
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        return max;
    }

    public Map<String, Long> getBalances(Address address) throws IOException {
        SimpleWorld world = this.loadWorld();
        Map<String, Long> balances = new HashMap<>();

        long depBalance = 0;
        Account account = world.get(address);
        if (account != null) {
            depBalance = account.getBalance().toLong();
        }
        balances.put("DEP", depBalance);

        long istBalance = 0;
        Address istContractAddr = Address.fromHexString("0x1111111111111111111111111111111111111111");
        Account contractAccount = world.get(istContractAddr);

        if (contractAccount != null) {
            UInt256 slotKey = UInt256.fromHexString(Utils.mappingSlot(address.toHexString(), 4));

            UInt256 storageValue = contractAccount.getStorageValue(slotKey);
            if (storageValue != null) {
                istBalance = storageValue.toLong();
            }
        }
        balances.put("IST", istBalance);

        return balances;
    }

    public String getBlockHash(int blockNumber) {
        if (blockchainData == null || !blockchainData.has(String.valueOf(blockNumber))) {
            return null;
        }
        JsonObject block = blockchainData.getAsJsonObject(String.valueOf(blockNumber));
        if (block.has("block_hash") && !block.get("block_hash").isJsonNull()) {
            return block.get("block_hash").getAsString();
        }
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static JsonArray transactionsToJson(List<Transaction> transactionList) {
        JsonArray jsonTransactions = new JsonArray();

        for (Transaction t : transactionList) {
            JsonObject txJson = new JsonObject();
            
            //txJson.addProperty("id", t.getId());
            txJson.addProperty("type", "CALL");
            txJson.addProperty("from", t.getSender().toHexString());

            if (t.getReceiver() != null) {
                txJson.addProperty("to", t.getReceiver().toHexString());
            } else {
                txJson.add("to", JsonNull.INSTANCE);
            }

            txJson.addProperty("input", t.getPayload());

            txJson.addProperty("gas_price", t.getGasPrice());
            txJson.addProperty("gas_limit", t.getGasLimit());
            txJson.addProperty("nonce", t.getNonce());

            jsonTransactions.add(txJson);
        }
        return jsonTransactions;
    }
}