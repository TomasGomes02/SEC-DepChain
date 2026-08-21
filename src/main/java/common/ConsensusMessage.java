package common;

import consensus.Node;
import consensus.QuorumCertificate;
import threshsig.SigShare;

public final class ConsensusMessage implements Message {
    private final MessageType type;
    private final int viewNumber;
    private final Node node;
    private final QuorumCertificate justify;
    private SigShare sigShare;
    

    public ConsensusMessage(MessageType type, int viewNumber, Node node, QuorumCertificate justify) {
        this.type = type;
        this.viewNumber = viewNumber;
        this.node = node;
        this.justify = justify;
        this.sigShare = null;
    }

    public MessageType getType() {
        return this.type;
    }

    public int getViewNumber() {
        return viewNumber;
    }

    public Node getNode() {
        return node;
    }

    public QuorumCertificate getJustify() {
        return justify;
    }

    public SigShare getSigShare() {
        return sigShare;
    }

    public void setSigShare(SigShare sigShare) {
        this.sigShare = sigShare;
    }
}
