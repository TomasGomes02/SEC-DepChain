package consensus;

import client.Client;
import common.MessageType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import contracts.BlockchainExecutor;
import contracts.BlockchainStateManager;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import transactions.Transaction;

public class BlockchainState {

    private int currView;
    private final List<BlockchainConsensus> members;
    private final List<Client> clients;
    //public final ArrayList<ClientRequest> pendingClientRequests;
    public final ArrayList<Transaction> pendingTransactions;
    private final ArrayList<Node> blockchain;
    private QuorumCertificate lockedQC;
    private QuorumCertificate prepareQC;
    private QuorumCertificate preCommitQC;
    private QuorumCertificate commitQC;
    private QuorumCertificate highQC;
    private ConsensusPhase phase;
    private final SimpleWorld worldState;
    private final BlockchainStateManager stateManager;
    private final BlockchainExecutor blockchainExecutor;

    public BlockchainState(int memberId) {
        this.currView = 1;
        this.members = new ArrayList<>();
        this.clients = new ArrayList<>();
        //this.pendingClientRequests = new ArrayList<>();
        this.pendingTransactions = new ArrayList<>();
        this.blockchain = new ArrayList<>();
        this.phase = ConsensusPhase.PREPARE;

        // load world state from genesis or last committed block
        this.stateManager = new BlockchainStateManager(
                "src/main/java/contracts/state" + memberId + ".json",
                "src/main/java/contracts/genesis.json"
        );
        try {
            this.worldState = stateManager.loadWorld();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load world state", e);
        }

        this.blockchainExecutor = new BlockchainExecutor(this.worldState);

        //Genesis block
        byte[] genesisHash = new byte[32];       // no transactions
        Node genesisNode = new Node(genesisHash, new ArrayList<>());
        QuorumCertificate genesisQC = new QuorumCertificate(MessageType.Decide, 0, genesisNode, null);

        this.blockchain.add(genesisNode);
        this.lockedQC = genesisQC;
        this.prepareQC = genesisQC;
        this.preCommitQC = genesisQC;
        this.commitQC = genesisQC;
        this.highQC = genesisQC;
    }

    public void addMember(BlockchainConsensus member){
        members.add(member);
    }

    public void addClient(Client client) {
        clients.add(client);
    }

    public boolean isLeader(int id){
        return getCurrViewLeader() == id;
    }

    public List<BlockchainConsensus> getMembers(){
        return this.members;
    }

    public List<Node> getBlockchain() {
    return this.blockchain;
}

    public QuorumCertificate getLockedQC() {
        return this.lockedQC;
    }

    public QuorumCertificate getPrepareQC(){
        return this.prepareQC;
    }

    public void nextView(){
        this.currView++;
    }

    public int getCurrView() {
        return this.currView;
    }

    public ConsensusPhase getPhase() {
        return phase;
    }

    public QuorumCertificate getHighQC() {
        return highQC;
    }

    public ConsensusPhase nextPhase() {
        switch(phase) {
            case PREPARE -> this.phase = ConsensusPhase.PRECOMMIT;
            case PRECOMMIT -> this.phase = ConsensusPhase.COMMIT;
            case COMMIT -> this.phase = ConsensusPhase.DECIDE;
            case DECIDE -> this.phase = ConsensusPhase.PREPARE;
        }
        return this.phase;
    }

    public int getNextViewLeader(){
        return ((currView - 1) % members.size()) + 1;
    }

    public int getCurrViewLeader(){
        int idx = ((currView - 1) % members.size()) + 1;
        if (idx == 0) {
            idx = members.size();
        }
        return idx;
    }

    public QuorumCertificate getPreCommitQC() {
        return preCommitQC;
    }

    public void setPreCommitQC(QuorumCertificate preCommitQC) {
        this.preCommitQC = preCommitQC;
    }

    public QuorumCertificate getCommitQC() {
        return commitQC;
    }

    public void setCommitQC(QuorumCertificate commitQC) {
        this.commitQC = commitQC;
    }

    public void setPrepareQC(QuorumCertificate prepareQC) {
        this.prepareQC = prepareQC;
    }

    public void setHighQC(QuorumCertificate highQC) {
        this.highQC = highQC;
    }

    public void setLockedQC(QuorumCertificate lockedQC) {
        this.lockedQC = lockedQC;
    }

    public BlockchainExecutor getBlockchainExecutor() { return blockchainExecutor; }

    public BlockchainStateManager getStateManager()   { return stateManager; }

    public SimpleWorld getWorldState()                { return worldState; }

    private final Map<String, Integer> addressToClientId = new HashMap<>();

    public void registerClient(int clientId, String address) {
        addressToClientId.put(address.toLowerCase(), clientId);
    }

    public Integer getClientIdByAddress(String address) {
        return addressToClientId.get(address.toLowerCase());
    }
}
