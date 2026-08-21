package secrets;

import threshsig.GroupKey;
import threshsig.KeyShare;


import utils.Utils;
import java.io.File;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;

public class KeyStoreManager {

    private final char[] password;
    private final KeyStore keyStore;

    public KeyStoreManager(File file, char[] password){
        this.password = password;
        try {
            this.keyStore = KeyStore.getInstance(file, password);
        } catch (KeyStoreException | NoSuchAlgorithmException | IOException | CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    public PublicKey getPublicKey(String alias) {
        try {
            java.security.cert.Certificate cert = keyStore.getCertificate(alias);
            if (cert == null) {
                throw new RuntimeException("No certificate found for alias: " + alias);
            }
            return cert.getPublicKey();
        } catch (KeyStoreException e) {
            throw new RuntimeException("Error accessing keystore for public key", e);
        }
    }

    public PrivateKey getPrivateKey(String alias) {
        try {
            return (PrivateKey) keyStore.getKey(alias, this.password);
        } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public GroupKey getGroupKey(String alias) {
        try {
            KeyStore.PasswordProtection pwd = new KeyStore.PasswordProtection(this.password);

            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(alias, pwd);

            byte[] serializedData = entry.getSecretKey().getEncoded();

            return (GroupKey) Utils.deserialize(serializedData, serializedData.length);

        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve GroupKey from keystore", e);
        }
    }


    public KeyShare getKeyShare(String alias) {
        try {
            KeyStore.PasswordProtection pwd = new KeyStore.PasswordProtection(this.password);

            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(alias, pwd);

            byte[] serializedData = entry.getSecretKey().getEncoded();

            return (KeyShare) Utils.deserialize(serializedData, serializedData.length);

        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve KeyShare from keystore", e);
        }
    }
}
