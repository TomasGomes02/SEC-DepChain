import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import contracts.BlockchainStateManager;
import logger.Logger;

import java.io.File;
import java.util.Map;

public class BlockchainStateManagerTest {

    @Test
    public void testStateLoadingAndBalances() {
        try {
            for (int i = 1; i <= 4; i++) {
            File dirtyState = new File("src/main/java/contracts/state" + i + ".json");
            if (dirtyState.exists()) dirtyState.delete(); 
            }
            
            Logger.test(null, "Running Test: Blockchain State Loading Check");

            // init state manager
            BlockchainStateManager stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state1.json",
                "src/main/java/contracts/genesis.json"
            );
            stateManager.loadWorld(); 

            // define the address of client
            Address clientAddress = Address.fromHexString("0xe2b8ef1fe43ed945ab77826ea1db5d4507129c28");
            
            // fetch the balances
            Map<String, Long> balances = stateManager.getBalances(clientAddress);

            // ensure the balances arent null
            assertNotNull(balances, "Balances map should not be null");
            assertTrue(balances.containsKey("DEP"), "State should contain DEP balance entry");
            assertTrue(balances.containsKey("IST"), "State should contain IST balance entry");

            long currentDep = balances.get("DEP");
            long currentIst = balances.get("IST");

            // balances must be positive
            assertTrue(currentDep >= 0, "DepCoin balance is negative (" + currentDep + ")");
            assertTrue(currentIst >= 0, "IST Coin balance is negative (" + currentIst + ")");

            Logger.test(null, "Test Passed: Client DEP: " + currentDep + ", IST: " + currentIst);

        } catch (Exception e) {
            fail("Exception thrown during test: " + e.getMessage());
        }
    }
}
