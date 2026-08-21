package utils;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Utils {
    public static byte[] serialize(Object msg)  {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos;
        try {
            oos = new ObjectOutputStream(bos);
            oos.writeObject(msg);
            oos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    public static Object deserialize(byte[] data, int length)  {
        ByteArrayInputStream bis = new ByteArrayInputStream(data, 0, length);
        ObjectInputStream ois;
        try {
            ois = new ObjectInputStream(bis);
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static String padHexStringTo256Bit(String hexString) {
        String stripped = strip0x(hexString);
        if (stripped.length() >= 64) return stripped.substring(0, 64);
        return "0".repeat(64 - stripped.length()) + stripped;
    }

    public static String strip0x(String hex) {
        return hex.startsWith("0x") ? hex.substring(2) : hex;
    }
    
    public static String convertIntegerToHex256Bit(int number) {
        return String.format("%064x", BigInteger.valueOf(number));
    }

    public static String convertLongToHex256Bit(long number) {
        return String.format("%064x", BigInteger.valueOf(number));
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            // Use HexFormat (Java 17+) or a manual loop for older versions
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String mappingSlot(String address, int mappingIndex) {
        String paddedAddress = String.format("%64s", getCleanAddress(address)).replace(' ', '0');
        String paddedIndex = String.format("%064x", mappingIndex);
        String concatenated = paddedAddress + paddedIndex;

        return "0x" + Numeric.toHexStringNoPrefix(
                Hash.sha3(Numeric.hexStringToByteArray(concatenated))
        );
    }

    private static String getCleanAddress(String address) {
        StringBuilder cleanAddress = new StringBuilder(address.toLowerCase().replace("0x", ""));

        while (cleanAddress.length() < 40) {
            cleanAddress.insert(0, "0");
        }
        if (cleanAddress.length() > 40) {
            cleanAddress = new StringBuilder(cleanAddress.substring(cleanAddress.length() - 40));
        }
        return cleanAddress.toString();
    }
}
