package client;

import java.io.Console;
import java.io.File;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import logger.Logger;
import secrets.KeyStoreManager;

public class Client implements Runnable {
    private ClientLibrary cl;
    String myAddress;


    public Client(int id, SocketAddress socketAddress, DatagramSocket socket, KeyStoreManager secrets) {
        this.cl = new ClientLibrary(id, socketAddress, socket, secrets);
    }

    public Client() {}

    private void clientService(ClientLibrary cl, Console console) throws InterruptedException {
        System.out.println("((((((((((((((((((((((((((((((        |||||||   |||||||||   |||||||    ||||||||  ||     ||   |||||||  ||||||  ||     ||");
        System.out.println("((((((((((((((((((((((((((((((        ||    ||  ||          ||    ||   ||        ||     ||  ||     ||   ||    |||    ||");
        System.out.println("((((((((((((((.  ((.  ((((((((        ||    ||  ||          ||    ||   ||        ||     ||  ||     ||   ||    ||||   ||");
        System.out.println("((((((((((((((   ((((((((((((         ||    ||  ||          ||    ||   ||        ||     ||  ||     ||   ||    || ||  ||");
        System.out.println("(((((((((  (((   (     /((((((        ||    ||  ||          ||    ||   ||        ||     ||  ||     ||   ||    ||  || ||");
        System.out.println("(((((((((  (((   ((,  ((((((((        ||    ||  ||          ||   ||    ||        ||     ||  ||     ||   ||    ||   ||||");
        System.out.println("@((((((((  (((   ((,  ((((((((        ||    ||  |||||||     |||||||    ||        |||||||||  |||||||||   ||    ||    |||");
        System.out.println("@((((((((  (((   ((,  ((((((((        ||    ||  ||          ||         ||        ||     ||  ||     ||   ||    ||     ||");
        System.out.println("@@((((((((((((   ((((((((((((@        ||    ||  ||          ||         ||        ||     ||  ||     ||   ||    ||     ||");
        System.out.println("@@@((((((  (((   (((((((((((@@        ||    ||  ||          ||         ||        ||     ||  ||     ||   ||    ||     ||");
        System.out.println("@@@@@(((((((((((((((((((((@@@@        ||    ||  ||          ||         ||        ||     ||  ||     ||   ||    ||     ||");
        System.out.println("@@@@@@@(((((((((((((((((@@@@@@        ||    ||  ||          ||         ||        ||     ||  ||     ||   ||    ||     ||");
        System.out.println("@@@@@@@@@@(((((((((((@@@@@@@@@        ||    ||  ||          ||         ||        ||     ||  ||     ||   ||    ||     ||");
        System.out.println("@@@@@@@@@@@@@(((((@@@@@@@@@@@@        |||||||   |||||||||   ||         ||||||||  ||     ||  ||     || ||||||  ||     ||");

        System.out.println("Welcome to DepChain! Type 'help' for commands or 'logout' to switch users.");

        cl.send("balance");

        Thread.sleep(500);

        while (true) {
            String req = console.readLine("DepChain (ID:" + cl.getId() + ")> ");

            if (req == null || req.trim().isEmpty()) continue;

            String command = req.trim().toLowerCase();

            if (command.equals("logout")) {
                return;
            }

            if (command.equals("help")) {
                System.out.println("\n--- Available Commands ---");
                System.out.println("  list / balance / behaviour / transferIST / transferDEP / transferFrom / approve");
                System.out.println("  logout (to switch users)");
                continue;
            }

            cl.send(req);
        }
    }

    @Override
    public void run() {
        File keyStoreFile = new File("all_members.p12");
        char[] password = "changeit".toCharArray();
        KeyStoreManager secrets = new KeyStoreManager(keyStoreFile, password);

        Console console = System.console();
        if (console == null) {
            Logger.error(null, "Console unavailable.");
            return;
        }

        while (true) {
            System.out.println("\n[Logged Out] Type 'login <id>' to start.");
            String input = console.readLine("DepChain> ");

            if (input == null) break;
            String[] parts = input.trim().split("\\s+");

            if (parts[0].equalsIgnoreCase("login") && parts.length == 2) {
                try {
                    int clientId = Integer.parseInt(parts[1]);
                    int clientPort = 5000 + clientId;

                    DatagramSocket socket = new DatagramSocket(clientPort);
                    InetSocketAddress addr = new InetSocketAddress("localhost", clientPort);

                    this.cl = new ClientLibrary(clientId, addr, socket, secrets);
                    this.cl.start();

                    System.out.println("\nLogged in as Client " + clientId);
                    clientService(this.cl, console);

                    this.cl.stop();
                    System.out.println("Logged out successfully.");

                } catch (NumberFormatException e) {
                    System.out.println("Invalid ID format.");
                } catch (Exception e) {
                    System.out.println("Login failed: " + e.getMessage());
                }
            } else if (parts[0].equalsIgnoreCase("exit")) {
                break;
            }
        }
    }

    public static void main(String[] args) {
        try {
            Client client = new Client();
            client.run();

        } catch (Exception e) {
            Logger.error(null, "Failed to start client: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
