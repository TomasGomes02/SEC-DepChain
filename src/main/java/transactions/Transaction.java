package transactions;

import org.hyperledger.besu.datatypes.Address;
import org.jetbrains.annotations.NotNull;
import utils.Utils;

import java.io.Serializable;
import java.security.PublicKey;
import java.util.Objects;

public class Transaction implements Comparable<Transaction>, Serializable {
    private final String id;
    private final String sender;
    private final String receiver;
    private final long amount;
    private final long gasPrice;
    private final long gasLimit;
    private final long nonce;
    private final String payload;
    private final PublicKey pub;
    private byte[] signature;


    public Transaction(String id, Address sender, Address receiver, long amount, long gasPrice, long gasLimit,
                       long nonce, String payload, PublicKey pub) {
        this.sender = sender.toString();
        this.receiver = receiver.toString();
        this.amount = amount;
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.nonce = nonce;
        this.payload = payload;
        this.pub = pub;
        this.id = calculateId();
        this.signature = null;
    }

    private String calculateId() {
        String data = sender + receiver + amount + gasPrice + gasLimit + nonce + payload + pub;
        return Utils.sha256Hex(data);
    }

    public String getId() {
        return id;
    }

    public Address getSender() {
        return (sender != null) ? Address.fromHexString(sender) : null;
    }

    public String getSenderAsString() { return sender; }

    public Address getReceiver() {
        return (receiver != null) ? Address.fromHexString(receiver) : null;
    }

    public long getAmount() {
        return amount;
    }

    public long getGasPrice() {
        return gasPrice;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public long getNonce() {
        return nonce;
    }

    public String getPayload() {
        return payload;
    }

    public void setSignature(byte[] sig) {
        signature = sig;
    }

    public byte[] getSignature() {
        return signature;
    }

    public PublicKey getSenderPublicKey() {
        return pub;
    }

    public boolean isValid() {
        return gasPrice > 0 && gasLimit > 0;// spec says neither can be zero
    }

    public long calculateFee(long gasUsed) {
        return gasPrice * Math.min(gasLimit, gasUsed);
    }

    public boolean isAborted(long gasUsed) {
        return gasUsed >= gasLimit;
    }

    @Override
    public int compareTo(@NotNull Transaction other) {
        long thisFee  = this.gasPrice  * this.gasLimit;
        long otherFee = other.gasPrice * other.gasLimit;

        // Highest fee descending
        int feeComparison = Long.compare(otherFee, thisFee);
        if (feeComparison != 0) {
            return feeComparison;
        }

        // Group by sender address
        int senderComparison = this.sender.compareTo(other.sender);
        if (senderComparison != 0) {
            return senderComparison;
        }
        // Order by nonce if its the same sender with the same fee
        int nonceComparison = Long.compare(this.nonce, other.nonce);
        if (nonceComparison != 0) {
            return nonceComparison;
        }

        return this.id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction other)) return false;
        return Objects.equals(this.id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
