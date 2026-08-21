package consensus;

import utils.CryptoUtils;

import java.io.Serializable;
import java.util.List;

import transactions.Transaction;


public class Node implements Serializable {
  byte[] parentHash;
  byte[] hash;
  List<Transaction> transactions;

  public Node(byte[] parent, List<Transaction> transactions) {
    this.parentHash = parent;
    this.transactions = transactions;
    this.hash = CryptoUtils.computeNodeHash(parent, transactions);
  }

  public byte[] getParentHash() {
    return this.parentHash;
  }

  public byte[] getHash() {
    return this.hash;
  }

  public List<Transaction> getTransactions() {
    return this.transactions;
  }
}
