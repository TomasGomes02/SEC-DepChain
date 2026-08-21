package utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import logger.Logger;

import java.io.FileReader;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;

public class WorldStateLoader {

    public static void loadWorldState(SimpleWorld worldState, String jsonFilePath) {
        try (FileReader reader = new FileReader(jsonFilePath)) {
            JsonObject rootObj = JsonParser.parseReader(reader).getAsJsonObject();
            
            JsonObject genesisBlock = rootObj.getAsJsonObject("0");
            
            // extract state dictionary
            JsonObject stateObj = genesisBlock.getAsJsonObject("state");

            // iterate through every account in the state
            for (Map.Entry<String, JsonElement> entry : stateObj.entrySet()) {
                String addressHex = entry.getKey();
                Address address = Address.fromHexString(addressHex);
                
                // the value is the account details
                JsonObject accountData = entry.getValue().getAsJsonObject();
                String type = accountData.get("type").getAsString();
                long balance = accountData.get("balance").getAsLong();
                long nonce = accountData.get("nonce").getAsLong();

                // create the account
                worldState.createAccount(address, nonce, Wei.of(balance));

                // if it is a Smart Contract, we also load its code and storage
                if ("CONTRACT".equals(type)) {
                    MutableAccount contractAccount = (MutableAccount) worldState.get(address);

                    String EVMBytecode = accountData.get("code").getAsString();
                    if (!EVMBytecode.startsWith("0x")) {
                        EVMBytecode = "0x" + EVMBytecode;
                    }
                    contractAccount.setCode(Bytes.fromHexString(EVMBytecode));

                    // load Storage key-value pairs
                    JsonObject storageObj = accountData.getAsJsonObject("storage");
                    if (storageObj != null) {
                        for (Map.Entry<String, JsonElement> storageEntry : storageObj.entrySet()) {
                            // slot key
                            UInt256 slotKey = UInt256.fromHexString(storageEntry.getKey());
                            // slot value
                            UInt256 slotValue = UInt256.fromHexString(storageEntry.getValue().getAsString());
                            
                            // save to EVM storage
                            contractAccount.setStorageValue(slotKey, slotValue);
                        }
                    }
                }
            }
            
            Logger.debug(null, "Genesis state successfully loaded into SimpleWorld");

        } catch (Exception e) {
            Logger.error(null, "Failed to load Genesis JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
