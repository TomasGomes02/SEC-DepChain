package consensus;

import common.MessageType;
import threshsig.SigShare;

import java.io.Serializable;

public record QuorumCertificate(MessageType type, int viewNumber, Node node, SigShare[] combinedSignature) implements Serializable {
}
