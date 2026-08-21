package member;

import consensus.ByzantineBehaviour;
import secrets.KeyStoreManager;

import java.io.File;
import java.net.*;
import java.util.ArrayList;

import consensus.BlockchainConsensus;

public class MemberService {
    public static void main(String[] args) throws Exception {
        File keyStoreFile = new File("all_members.p12");
        char[] password = "changeit".toCharArray();
        KeyStoreManager secrets = new KeyStoreManager(keyStoreFile, password);

        ArrayList<BlockchainConsensus> members = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            InetSocketAddress addr = new InetSocketAddress("localhost", 8000 + i);
            if (i == 1) {
                members.add(new BlockchainConsensus(i, addr, new DatagramSocket(8000 + i), secrets, ByzantineBehaviour.NONE));
            }
            else {
                members.add(new BlockchainConsensus(i, addr, new DatagramSocket(8000 + i), secrets, ByzantineBehaviour.NONE));
            }
        }

        for (BlockchainConsensus member : members) {
            for (BlockchainConsensus memberToAdd : members) {
                member.getState().addMember(memberToAdd);
            }
            for (int clientId = 10; clientId < 14; clientId++) {
                int clientPort = 5000 + clientId;
                member.addClientConnection(clientId, new InetSocketAddress("localhost", clientPort));
            }
            member.createConnections();
            new Thread(member).start();
        }
    }
}