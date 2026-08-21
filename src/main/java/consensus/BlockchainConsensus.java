package consensus;

import common.*;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.web3j.crypto.Hash;

import secrets.KeyStoreManager;
import threshsig.GroupKey;
import threshsig.KeyShare;
import threshsig.SigShare;
import transactions.Transaction;
import utils.CryptoUtils;
import utils.TimeoutHandler;
import utils.Utils;
import communication.AuthenticatedPerfectLink;
import logger.Logger;

import java.io.IOException;
import java.net.*;
import java.security.KeyPair;
import java.util.*;

public class BlockchainConsensus implements Runnable {
    private final int id;
    private final SocketAddress socketAddress;
    private final DatagramSocket socket;
    private final Map<Integer, AuthenticatedPerfectLink> connections;
    private final Map<Integer, AuthenticatedPerfectLink> clientConnections;
    private final KeyStoreManager secrets;
    private final GroupKey groupKey;
    private final KeyShare share;
    private final BlockchainState state;
    private final int f = 1; //faults
    private final int n = 3 * f + 1; //replicas
    private final int quorum = n - f; //quorum
    public final long MAX_BLOCK_GAS = 500_000L;
    public final int MIN_TRANSACTIONS = 2;
    private long batchStartTime = 0;
    private final ByzantineBehaviour behaviour;

    private final Map<Integer, Map<MessageType, List<ConsensusMessage>>> votes;

    private boolean readyToPropose = false; //keep track of whether the leader is waiting to propose
    private final TimeoutHandler timeoutHandler;

    public int getId() { return this.id; }

    public BlockchainConsensus(int id, SocketAddress socketAddress, DatagramSocket socket, KeyStoreManager secrets,
                               ByzantineBehaviour behaviour) {
        this.id = id;
        this.socketAddress = socketAddress;
        this.socket = socket;
        this.connections = new HashMap<>();
        this.clientConnections = new HashMap<>();
        this.state = new BlockchainState(id);
        this.votes = new HashMap<>();
        this.groupKey = secrets.getGroupKey("master_public_key");
        this.secrets = secrets;
        this.share = secrets.getKeyShare("member" + id + "_threshold_share");
        this.timeoutHandler = new TimeoutHandler(this);
        this.behaviour = behaviour;
    }

    @Override
    public void run() {
        this.connections.values().forEach(AuthenticatedPerfectLink::beginHandshake);
        boolean initialViewSent = false;

        while(true){
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                this.socket.receive(packet);
            } catch (IOException e) {
                if (this.socket.isClosed())
                    break;
                throw new RuntimeException(e);
            }
            Object message = Utils.deserialize(packet.getData(), packet.getLength());
            if (message instanceof Message){
                SocketAddress senderAddress = packet.getSocketAddress();
                AuthenticatedPerfectLink apl = this.getConnection(senderAddress);
                if (apl != null){
                    try {
                        if (apl.deliver((Message) message)) {
                            if (message instanceof AuthenticatedMessage authMsg){
                                Object payload = authMsg.getMsg();

                                if (payload instanceof ConsensusMessage msg) {
                                    handleConsensusMessage(msg, apl.getPeerId());
                                }
                                else if (payload instanceof ClientMessage msg) {
                                    if (msg.getTransaction() == null) {
                                        Logger.info("" + this.id, "Received client balance request.");
                                        handleBalanceRequest(msg, apl);
                                        continue;
                                    }
                                    byte[] msgData = Utils.serialize(new Object[]{
                                            msg.getTransaction(), msg.getNonce(), msg.getPublicKey()
                                    });
                                    if (!CryptoUtils.verify(msg.getPublicKey(), msgData, msg.getSignature())) {
                                        Logger.warn("" + this.id, "Invalid client signature dropped.");
                                        apl.send(new ClientReplyMessage("[Error] Invalid client signature."));
                                        continue;
                                    }

                                    // verify nonce
                                    Transaction trans = msg.getTransaction();
                                    MutableAccount senderAccount = (MutableAccount) state.getWorldState().get(trans.getSender());
                                    long trueServerNonce = senderAccount.getNonce();

                                    if (trans.getNonce() < trueServerNonce) {
                                        Logger.warn("" + this.id, String.format("Invalid nonce dropped. Expected: %d, Got: %d", 
                                                        trueServerNonce, trans.getNonce()));
                                        
                                        apl.send(new ClientReplyMessage("[Error] Invalid client nonce."));
                                        continue;
                                    }

                                    boolean alreadyInBlock = false;

                                    // look through all transactions currently waiting to be put in a block
                                    for (Transaction pendingTx : state.pendingTransactions) {
                                        // if we find a transaction from the same sender with the same nonce
                                        if (pendingTx.getSender().equals(trans.getSender()) && pendingTx.getNonce() == trans.getNonce()) {
                                            alreadyInBlock = true;
                                            break;
                                        }
                                    }
                                    if (alreadyInBlock) {
                                        Logger.warn("" + this.id, "Duplicate nonce dropped. Transaction is already waiting in the block");
                                        apl.send(new ClientReplyMessage("[Error] Transaction with this nonce is already pending"));
                                        continue;
                                    }

                                    Logger.info("" + this.id, "Valid transaction received from "
                                            + msg.getTransaction().getSender());
                                    state.pendingTransactions.add(msg.getTransaction());

                                    if (!state.isLeader(this.id)) {
                                        timeoutHandler.resetTimeout();
                                    }
                                    tryPropose();
                                }
                            }


                        }
                    } catch (IOException | ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            if (!initialViewSent && allSessionKeysEstablished()) {
                Logger.info("" + this.id, "All peer session keys established!");
                sendNewView();
                initialViewSent = true;
                if (this.behaviour == ByzantineBehaviour.CRASH) {
                    Logger.info("" + this.id, "[BYZANTINE] Crashing in 5 seconds...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    return;
                }
            }
        }
    }

    public void stop() {
        if (this.socket != null && !this.socket.isClosed()) {
            this.socket.close();
        }
    }

    private boolean allSessionKeysEstablished() {
        for (AuthenticatedPerfectLink link : this.connections.values()) {
            if (link.getSessionKey() == null) {
                return false;
            }
        }
        return true;
    }

    public void createConnections(){
        this.state.getMembers().forEach(
                m -> {
                    if (m.id == this.id) return;
                    try {
                        KeyPair keyPair = new KeyPair(
                                secrets.getPublicKey("member" + m.id + "_pub"),
                                secrets.getPrivateKey("member" + id + "_priv"));
                        this.connections.put(m.id, new AuthenticatedPerfectLink(this.socket, m.socketAddress, this.id,
                                m.id, keyPair));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private AuthenticatedPerfectLink getConnection(SocketAddress address){
        int port = ((InetSocketAddress) address).getPort(); // Cast to InetSocketAddress to easily extract the port

        for (AuthenticatedPerfectLink link : this.connections.values()) {
            if (((InetSocketAddress) link.getSocketAddress()).getPort() == port)
                return link;
        }
        for (AuthenticatedPerfectLink link : this.clientConnections.values()) {
            if (((InetSocketAddress) link.getSocketAddress()).getPort() == port)
                return link;
        }
        return null;
    }

    public void addClientConnection(int clientId, SocketAddress clientAddress) {
        try {
            KeyPair keyPair = new KeyPair(
                    secrets.getPublicKey("client" + clientId + "_pub"),
                    secrets.getPrivateKey("member" + id + "_priv"));
            this.clientConnections.put(clientId, new AuthenticatedPerfectLink(
                    this.socket, clientAddress, this.id, clientId, keyPair));

            String clientAddr = deriveAddress(secrets.getPublicKey("client" + clientId + "_pub"));
            state.registerClient(clientId, clientAddr);
            Logger.debug("" + this.id, "Registered client " + clientId
                    + " at address " + clientAddr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String deriveAddress(java.security.PublicKey pub) {
        byte[] pubKeyBytes = pub.getEncoded();
        byte[] hashedKey = Hash.sha3(pubKeyBytes);
        byte[] addressBytes = Arrays.copyOfRange(hashedKey, hashedKey.length - 20, hashedKey.length);
        return Bytes.wrap(addressBytes).toHexString();
    }

    private void handleBalanceRequest(ClientMessage msg, AuthenticatedPerfectLink apl) {
        try {
            Address requester = Address.fromHexString(deriveAddress(msg.getPublicKey()));

            Map<String, Long> balances = state.getStateManager().getBalances(requester);

            MutableAccount requesterAccount = (MutableAccount) state.getWorldState().get(requester);
            long requesterNonce = requesterAccount.getNonce();

            String response = String.format(
                "\n--- Local Balance Check ---\nAddress: %s\nDepCoin: %d\nISTCoin: %d\nNonce: %d\n---------------------------",
                requester.toHexString(),
                balances.get("DEP"),
                balances.get("IST"),
                requesterNonce
            );

            apl.send(new ClientReplyMessage(response));

        } catch (IOException e) {
            Logger.error("" + this.id, "[Consensus] Balance query failed: " + e.getMessage());
            apl.send(new ClientReplyMessage("Error: Local state temporarily unavailable."));
        }
    }

    public void broadcast(Message message) {
        Logger.info("" + this.id, "Broadcasting: " + message.toString());
        this.connections.values().forEach(link -> link.send(message));
        if (message instanceof ConsensusMessage msg && msg.getType() == MessageType.Prepare) {
            handleConsensusMessage(msg, this.id);
        }
    }

    private void sendVote(ConsensusMessage vote, int leaderId) {
        switch (behaviour) {
            case NO_RESPONSE -> {
            }
            case NONE -> {
                if (leaderId == this.id) {
                    new Thread(() -> {
                        synchronized (BlockchainConsensus.this) {
                            onReceiveVote(vote);
                        }
                    }).start();
                } else {
                    AuthenticatedPerfectLink apl = connections.get(leaderId);
                    if (apl != null) apl.send(vote);
                }
            }
            case RANDOM_MESSAGE -> {
                ConsensusMessage wrongVote = generateRandomMessage();
                if (leaderId == this.id) {
                    new Thread(() -> {
                        synchronized (BlockchainConsensus.this) {
                            onReceiveVote(wrongVote);
                        }
                    }).start();
                } else {
                    AuthenticatedPerfectLink apl = connections.get(leaderId);
                    if (apl != null) apl.send(wrongVote);
                }
            }
        }
    }

    public void sendNewView(){
        if (Objects.requireNonNull(behaviour) == ByzantineBehaviour.NO_RESPONSE) {
            Logger.info("" + this.id, "[BYZANTINE] Not sending new view vote");
            return;
        }
        int leaderId = state.getCurrViewLeader();
        ConsensusMessage newViewMsg = new ConsensusMessage(MessageType.NewView, state.getCurrView(), null, state.getPrepareQC());
        Logger.info("" + this.id, "Sending new view to " + leaderId);
        if (leaderId == this.id) {
            onReceiveNewView(newViewMsg);
        } else {
            AuthenticatedPerfectLink apl = connections.get(leaderId);
            if (apl != null) apl.send(newViewMsg);
        }
        if (!state.pendingTransactions.isEmpty()) {
            timeoutHandler.resetTimeout();
        }
    }

    public synchronized void handleConsensusMessage(ConsensusMessage msg, int sender) {
        if (this.behaviour == ByzantineBehaviour.NO_RESPONSE) {
            Logger.info("" + this.id, "[BYZANTINE] Not replying to any message");
            return;
        }
        if (msg.getViewNumber() != state.getCurrView()) return;
        Logger.info("" + this.id, "Handling message " + msg.getType());
        switch (msg.getType()) {
            case Prepare -> onReceivePrepare(msg, sender);
            case PreCommit -> onReceivePreCommit(msg, sender);
            case Commit -> onReceiveCommit(msg, sender);
            case Decide -> onReceiveDecide(msg, sender);
            case NewView -> onReceiveNewView(msg);
            case PrepareVote, PreCommitVote, CommitVote -> onReceiveVote(msg);
        }
    }

    private ConsensusMessage generateRandomMessage() {
        Random r = new Random();
        MessageType[] types = MessageType.values();
        byte[] randomHash = new byte[32];
        r.nextBytes(randomHash);
        byte[] randomCMD = new byte[10];
        r.nextBytes(randomCMD);
        return new ConsensusMessage(
                types[r.nextInt(types.length)],
                r.nextInt(1, 50),
                new Node(randomHash, new ArrayList<>()),
                new QuorumCertificate(
                        types[r.nextInt(types.length)],
                        r.nextInt(1, 50),
                        new Node(randomHash, new ArrayList<>()),
                        new SigShare[]{}));
    }

    // ── Replica handlers ──────────────────────────────────────────────────────

    public void onReceivePrepare(ConsensusMessage msg, int sender) {
        if (state.isLeader(sender) && matchingMsg(msg, MessageType.Prepare, state.getCurrView())){

            //null node proposed on message
            if (msg.getNode() == null) {
                Logger.info("" + this.id, "Prepare message has null node, dropped.");
                timeoutHandler.resetTimeout();
                return;
            }

            if (msg.getNode().getTransactions().isEmpty() || msg.getNode().getTransactions() == null) {
                Logger.info("" + this.id, "Prepare message has empty transaction list, dropped.");
                timeoutHandler.resetTimeout();
                return;
            }

            long currentBlockGas = 0;

            for (Transaction t : msg.getNode().getTransactions()) {

                //byzantine could inject transactions with forged signatures
                byte[] expected = t.getId().getBytes();
                if (!CryptoUtils.verify(t.getSenderPublicKey(), expected, t.getSignature())) {
                    Logger.warn("" + this.id, "Proposed transaction has invalid signature, dropped.");
                    timeoutHandler.resetTimeout();
                    return;
                }

                //gas price and gas limit cannot be zero
                if (!t.isValid()) {
                    Logger.warn("" + this.id, "Proposed transaction has zero gas fields, dropped.");
                    timeoutHandler.resetTimeout();
                    return;
                }

                //sender must have enough balance to cover the fee
                MutableAccount senderAccount = (MutableAccount) state.getWorldState().get(t.getSender());
                if (senderAccount == null){
                    Logger.warn("" + this.id, "Proposed transaction sender account not found, dropped.");
                    timeoutHandler.resetTimeout();
                    return;
                }
                long maxFee = t.getGasPrice() * t.getGasLimit();
                if (senderAccount.getBalance().toLong() < maxFee + t.getAmount()) {
                    Logger.warn("" + this.id, "Proposed transaction sender has insufficient balance, dropped.");
                    timeoutHandler.resetTimeout();
                    return;
                }
                currentBlockGas += t.getGasLimit();
                if(currentBlockGas>MAX_BLOCK_GAS){
                    Logger.warn("" + this.id, "Block rejected: Cumulative gas (" + currentBlockGas + ") exceeds MAX_BLOCK_GAS");
                    timeoutHandler.resetTimeout();
                    return;
                }
            }

            List<Transaction> ts = msg.getNode().getTransactions();
            for (int i = 0; i < ts.size() - 1; i++){
                Transaction curr = ts.get(i);
                Transaction next = ts.get(i + 1);
                if(curr.compareTo(next) > 0){
                    Logger.warn("" + this.id, "Block rejected: Leader failed to sort transactions deterministically");
                    timeoutHandler.resetTimeout();
                    return;
                }
            }

            boolean isGenesisQC = (msg.getJustify().viewNumber() == 0);

            boolean isValidQC = isGenesisQC || CryptoUtils.tverify(
                    msg.getJustify().combinedSignature(),
                    groupKey,
                    msg.getJustify().type(),
                    msg.getJustify().viewNumber(),
                    msg.getJustify().node()
            );

            if(isValidQC) {
                if (extendsFrom(msg.getNode(), msg.getJustify().node()) && safeNode(msg.getNode(), msg.getJustify())) {
                    timeoutHandler.resetTimeout();
                    state.nextPhase();
                    sendVote(voteMessage(MessageType.PrepareVote, msg.getNode(), state.getPrepareQC()), sender);
                }
            } else {
                Logger.warn("" + this.id, "Rejected Prepare message due to invalid HighQC signature.");
                timeoutHandler.resetTimeout();
            }
        }
    }

    public void onReceivePreCommit(ConsensusMessage msg, int sender) {
        if (state.isLeader(sender) && matchingQC(msg.getJustify(), MessageType.Prepare, state.getCurrView())) {
            timeoutHandler.resetTimeout();
            state.setPrepareQC(msg.getJustify());
            state.nextPhase();
            sendVote(voteMessage(MessageType.PreCommitVote, msg.getJustify().node(), null), sender);
        }
    }

    public void onReceiveCommit(ConsensusMessage msg, int sender) {
        if (state.isLeader(sender) && matchingQC(msg.getJustify(), MessageType.PreCommit, state.getCurrView())) {

            timeoutHandler.resetTimeout();
            state.setLockedQC(msg.getJustify());
            state.nextPhase();
            sendVote(voteMessage(MessageType.CommitVote, msg.getJustify().node(), null), sender);
        }
    }

    public void onReceiveDecide(ConsensusMessage msg, int sender) {
        if (!state.isLeader(this.id) && state.isLeader(sender)) {
            if (matchingQC(msg.getJustify(), MessageType.Commit, state.getCurrView())) {
                commitBlock(msg.getJustify().node());
                state.nextView();
                state.nextPhase();
                sendNewView();
                timeoutHandler.resetTimeout();
            }
        }
    }

    // ── Leader handler ────────────────────────────────────────────────────────

    public void onReceiveVote(ConsensusMessage msg) {
        if (Objects.requireNonNull(behaviour) == ByzantineBehaviour.NO_RESPONSE) {
            Logger.info("" + this.id, "Not sending any vote responses");
            return;
        }

        if (!initVoteList(msg)) return;

        switch (state.getPhase()) {
            case PRECOMMIT -> {
                List<ConsensusMessage> prepareVotes =
                        votes.get(state.getCurrView()).get(MessageType.PrepareVote);
                if (prepareVotes != null && prepareVotes.size() >= quorum) {
                    SigShare[] combinedSigs = collectSigs(prepareVotes);
                    ConsensusMessage first = prepareVotes.getFirst();
                    if (!CryptoUtils.tverify(combinedSigs, groupKey, toBaseType(first.getType()), first.getViewNumber(), first.getNode())) {
                        return;
                    }
                    state.setPrepareQC(new QuorumCertificate(
                            MessageType.Prepare, first.getViewNumber(), first.getNode(), combinedSigs));
                    state.nextPhase();
                    broadcast(new ConsensusMessage(MessageType.PreCommit, state.getCurrView(), null, state.getPrepareQC()));
                    ConsensusMessage myVote = voteMessage(MessageType.PreCommitVote, first.getNode(), null);
                    initVoteList(myVote);
                }
            }
            case COMMIT -> {
                List<ConsensusMessage> preCommitVotes =
                        votes.get(state.getCurrView()).get(MessageType.PreCommitVote);
                if (preCommitVotes != null && preCommitVotes.size() >= quorum) {
                    SigShare[] combinedSigs = collectSigs(preCommitVotes);
                    ConsensusMessage first = preCommitVotes.getFirst();
                    if (!CryptoUtils.tverify(combinedSigs, groupKey, toBaseType(first.getType()), first.getViewNumber(), first.getNode())) {
                        return;
                    }
                    state.setPreCommitQC(new QuorumCertificate(
                            MessageType.PreCommit, first.getViewNumber(), first.getNode(), combinedSigs));
                    state.nextPhase();
                    broadcast(new ConsensusMessage(MessageType.Commit, state.getCurrView(), null, state.getPreCommitQC()));
                    ConsensusMessage myVote = voteMessage(MessageType.CommitVote, first.getNode(), null);
                    initVoteList(myVote);
                }
            }
            case DECIDE -> {
                List<ConsensusMessage> commitVotes =
                        votes.get(state.getCurrView()).get(MessageType.CommitVote);
                if (commitVotes != null && commitVotes.size() >= quorum) {
                    SigShare[] combinedSigs = collectSigs(commitVotes);
                    ConsensusMessage first = commitVotes.getFirst();
                    if (!CryptoUtils.tverify(combinedSigs, groupKey, toBaseType(first.getType()), first.getViewNumber(), first.getNode())) {
                        return;
                    }
                    state.setCommitQC(new QuorumCertificate(
                            MessageType.Commit, first.getViewNumber(), first.getNode(), combinedSigs));
                    state.nextPhase();
                    broadcast(new ConsensusMessage(MessageType.Decide, state.getCurrView(), null, state.getCommitQC()));
                    commitBlock(first.getNode());
                    state.nextView();
                    sendNewView();
                    timeoutHandler.resetTimeout();
                }
            }
        }
    }

    private boolean initVoteList(ConsensusMessage msg) {
        if (!state.isLeader(this.id))
            return false;

        int view = msg.getViewNumber();
        MessageType type = msg.getType();

        votes.putIfAbsent(view, new HashMap<>());
        votes.get(view).putIfAbsent(type, new ArrayList<>());
        votes.get(msg.getViewNumber()).get(msg.getType()).add(msg);
        timeoutHandler.resetTimeout();
        return true;
    }

    private void onReceiveNewView(ConsensusMessage msg) {
        if (!initVoteList(msg)) return;

        List<ConsensusMessage> newViews = votes.get(state.getCurrView()).get(MessageType.NewView);
        if (newViews != null && newViews.size() == quorum) {
            QuorumCertificate highQC = newViews.stream()
                    .map(ConsensusMessage::getJustify)
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(QuorumCertificate::viewNumber))
                    .orElse(state.getHighQC());
            state.setHighQC(highQC);
            this.readyToPropose = true;
            tryPropose();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void commitBlock(Node node) {
        state.getBlockchain().add(node);
        Logger.info("" + this.id, "BLOCK COMMITTED FOR VIEW "
                + state.getCurrView()
                + " with " + node.getTransactions().size() + " transactions");

        Map<String, Long> transactionFees = this.state.getBlockchainExecutor().executeBlock(node.transactions);

        try {
            this.state.getStateManager().appendBlock(
                    this.state.getWorldState(),
                    node.transactions
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        state.pendingTransactions.removeAll(node.getTransactions());

        for (Transaction t : node.getTransactions()) {
            String senderHex = t.getSenderAsString();
            Integer clientId = state.getClientIdByAddress(senderHex);
            if (clientId != null) {
                AuthenticatedPerfectLink link = clientConnections.get(clientId);
                if (link != null) {
                    Long fee = transactionFees.getOrDefault(t.getId(), 0L);
                    link.send(new ClientReplyMessage(
                            "Transaction from " + senderHex
                                    + " committed in view " + state.getCurrView()
                                    + ". Fee paid: " + fee + " DEP"));
                }
            }
        }
    }

    private SigShare[] collectSigs(List<ConsensusMessage> msgs) {
        return msgs.stream()
                .map(ConsensusMessage::getSigShare)
                .filter(Objects::nonNull)
                .toArray(SigShare[]::new);
    }

    private boolean safeNode(Node node, QuorumCertificate qc) {
        QuorumCertificate lockedQC = this.state.getLockedQC();
        //If parent hash from this node is the same as the hash of the node we are comparing to
        boolean safe = extendsFrom(node, lockedQC.node());
        //Current view is more recent than the QC we are comparing to
        boolean live = qc.viewNumber() > lockedQC.viewNumber();

        return safe || live;
    }

    private boolean extendsFrom(Node self, Node parent)  {
        return Arrays.equals(self.getParentHash(), parent.getHash());
    }

    public ConsensusMessage voteMessage(MessageType type, Node node, QuorumCertificate qc) {
        int currView = state.getCurrView();
        ConsensusMessage msg = new ConsensusMessage(type, currView, node, qc);
        MessageType sigType = toBaseType(type);
        // dar hash a (MessageType + viewNumber + node)
        byte[] dataToSign = CryptoUtils.computeSignHash(sigType, currView, node);

        threshsig.SigShare sigShare = share.sign(dataToSign);

        msg.setSigShare(sigShare);

        return msg;
    }

    private boolean matchingMsg(ConsensusMessage m, MessageType t, int v) {
        return m.getType() == t && m.getViewNumber() == v;
    }

    private boolean matchingQC(QuorumCertificate qc, MessageType t, int v) {
        return qc.type() == t && qc.viewNumber() == v;
    }

    private MessageType toBaseType(MessageType voteType) {
        return switch (voteType) {
            case PrepareVote   -> MessageType.Prepare;
            case PreCommitVote -> MessageType.PreCommit;
            case CommitVote    -> MessageType.Commit;
            default            -> voteType;
        };
    }

    public BlockchainState getState() {
        return this.state;
    }

    private synchronized void tryPropose() {
        if (Objects.requireNonNull(behaviour) == ByzantineBehaviour.NO_RESPONSE) {
            Logger.info("" + this.id, "[BYZANTINE] Not proposing any messages");
        }
        if (state.isLeader(this.id) && this.readyToPropose && !state.pendingTransactions.isEmpty()) {

            if (batchStartTime == 0) {
                batchStartTime = System.currentTimeMillis();

                new Thread(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    tryPropose();
                }).start();
            }

            long pendingGas = state.pendingTransactions.stream().mapToLong(Transaction::getGasLimit).sum();
            boolean isBlockFull = pendingGas >= MAX_BLOCK_GAS * 0.8;
            boolean isTimeout = (System.currentTimeMillis() - batchStartTime) >= 10000; // wait max 2 seconds

            boolean isByzantine = (behaviour != ByzantineBehaviour.NONE && behaviour != ByzantineBehaviour.NO_RESPONSE);

            if (isBlockFull || isTimeout || isByzantine) {
                this.readyToPropose = false;
                this.batchStartTime = 0;
                QuorumCertificate highQC = state.getHighQC();
                //ClientRequest req = state.pendingClientRequests.getFirst();

                switch (behaviour) {
                    case BAD_SORTING -> {
                        List<Transaction> sorted = new ArrayList<>(state.pendingTransactions);
                        Collections.sort(sorted);

                        Collections.reverse(sorted);

                        List<Transaction> blockTransactions = new ArrayList<>();
                        long currentBlockGas = 0;

                        for (Transaction t : sorted){
                            if (currentBlockGas + t.getGasLimit() <= MAX_BLOCK_GAS) {
                                blockTransactions.add(t);
                                currentBlockGas += t.getGasLimit();
                            }
                        }

                        assert highQC != null;
                        Node curProposal = new Node(highQC.node().getHash(), blockTransactions);

                        Logger.info("" + this.id, "[BYZANTINE] Proposing maliciously sorted block to frontrun!");

                        broadcast(new ConsensusMessage(MessageType.Prepare, state.getCurrView(), curProposal, highQC));
                        timeoutHandler.resetTimeout();
                    }
                    case OVERSIZED_BLOCK -> {
                        List<Transaction> allTs = new ArrayList<>(state.pendingTransactions);
                        Collections.sort(allTs);

                        assert highQC != null;
                        Node curProposal = new Node(highQC.node().getHash(), allTs);

                        long totalGas = allTs.stream().mapToLong(Transaction::getGasLimit).sum();
                        Logger.info("" + this.id, "[BYZANTINE] Proposing OVERSIZED block with "
                                + allTs.size() + " ts (Total Gas: " + totalGas + ")");

                        broadcast(new ConsensusMessage(MessageType.Prepare, state.getCurrView(), curProposal, highQC));
                        timeoutHandler.resetTimeout();
                    }
                    case WRONG_COMMAND -> {
                        assert highQC != null;

                        Node curProposal = new Node(highQC.node().getHash(), new ArrayList<>());

                        Logger.info("" + this.id, "[BYZANTINE] Proposing wrong (empty) block");

                        broadcast(new ConsensusMessage(MessageType.Prepare, state.getCurrView(), curProposal, highQC));
                        timeoutHandler.resetTimeout();
                    }
                    case RANDOM_MESSAGE -> {
                        ConsensusMessage randomMessage = generateRandomMessage();
                        Logger.info("" + this.id, "[BYZANTINE] Proposing a random block");

                        broadcast(randomMessage);
                        timeoutHandler.resetTimeout();
                    }
                    case NONE -> {
                        List<Transaction> sorted = new ArrayList<>(state.pendingTransactions);
                        Collections.sort(sorted);
                        List<Transaction> blockTransactions = new ArrayList<>();
                        long currentBlockGas = 0;

                        for (Transaction t : sorted){
                            if (currentBlockGas + t.getGasLimit() <= MAX_BLOCK_GAS) {
                                blockTransactions.add(t);
                                currentBlockGas+=t.getGasLimit();
                            }
                            //if massive transaction doesnt fit, loop skips it and sees if smaller transaction further down
                            //the list fits
                        }

                        assert highQC != null;

                        Node curProposal = new Node(highQC.node().getHash(), blockTransactions);

                        Logger.info("" + this.id, "Proposing block with "
                                + blockTransactions.size() + " transactions (Total Gas: "
                                + currentBlockGas + "/" + MAX_BLOCK_GAS + ")");

                        broadcast(new ConsensusMessage(MessageType.Prepare, state.getCurrView(), curProposal, highQC));
                        timeoutHandler.resetTimeout();
                    }
                }
            }
        }
    }

    public void setReadyToPropose(boolean value) {
        this.readyToPropose = value;
        if (value) {
            tryPropose();
        }
    }
}
