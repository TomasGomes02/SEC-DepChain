import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import utils.ThreshSigSetup;

import static org.junit.jupiter.api.Assertions.*;

class ThreshSigSetupTest {

    @TempDir
    Path tempDir;

    @Test
    void testGenerateCreatesValidKeystore() throws Exception {
        int nClients = 3;

        // Arrange
        int n = 4; // Number of members
        int t = 1; // Threshold
        int testKeySize = 2048; // Use a smaller key size (2048) so the test runs quickly
        String password = "changeit";
        Path keystorePath = tempDir.resolve("test_members.p12");
        char[] pwdChars = password.toCharArray();

        // Act
        ThreshSigSetup.generate(nClients, n, t, testKeySize, password, keystorePath.toString());

        // Assert 1: Ensure the file was actually created
        assertTrue(Files.exists(keystorePath), "Keystore file should be created");

        // Assert 2: Load the Keystore to verify it is a valid PKCS12 file
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(keystorePath)) {
            ks.load(is, pwdChars);
        }

        // Assert 3: Verify Master Public Key exists
        assertTrue(ks.containsAlias("master_public_key"), "Keystore should contain the master public key");
        KeyStore.Entry masterEntry = ks.getEntry("master_public_key", new KeyStore.PasswordProtection(pwdChars));
        assertTrue(masterEntry instanceof KeyStore.SecretKeyEntry, "Master key should be stored as a SecretKeyEntry");

        // Assert 4: Verify entries for each member
        for (int i = 1; i <= n; i++) {
            String aliasPriv = "member" + i + "_priv";
            String aliasPub = "member" + i + "_pub";
            String aliasShare = "member" + i + "_threshold_share";

            // 4a. Check aliases exist
            assertTrue(ks.containsAlias(aliasPriv), "Missing private key for member " + i);
            assertTrue(ks.containsAlias(aliasPub), "Missing public cert for member " + i);
            assertTrue(ks.containsAlias(aliasShare), "Missing threshold share for member " + i);


            // 4b. Verify Private Key
            KeyStore.Entry privEntry = ks.getEntry(aliasPriv, new KeyStore.PasswordProtection(pwdChars));
            assertTrue(privEntry instanceof KeyStore.PrivateKeyEntry);
            PrivateKey privateKey = ((KeyStore.PrivateKeyEntry) privEntry).getPrivateKey();
            assertEquals("RSA", privateKey.getAlgorithm(), "Member private key should be RSA");

            // 4c. Verify Certificate details (Self-Signed)
            Certificate cert = ks.getCertificate(aliasPub);
            assertNotNull(cert, "Certificate should not be null");
            assertTrue(cert instanceof X509Certificate);
            
            X509Certificate x509Cert = (X509Certificate) cert;
            x509Cert.checkValidity(); // Validates dates (throws exception if expired/not yet valid)
            
            String subjectDN = x509Cert.getSubjectX500Principal().getName();
            assertTrue(subjectDN.contains("CN=member" + i), "Certificate subject should contain CN=member" + i);
            
            // 4d. Verify Threshold Share 
            KeyStore.Entry shareEntry = ks.getEntry(aliasShare, new KeyStore.PasswordProtection(pwdChars));
            assertTrue(shareEntry instanceof KeyStore.SecretKeyEntry, "Threshold share should be stored as a SecretKeyEntry");
        }

        // Assert 5: Verify entries for each client
        for (int i = 10; i < nClients + 10; i++) {
            String aliasPriv = "client" + i + "_priv";
            String aliasPub = "client" + i + "_pub";

            // 5a. Check aliases exist
            assertTrue(ks.containsAlias(aliasPriv), "Missing private key for client " + i);
            assertTrue(ks.containsAlias(aliasPub), "Missing public cert for client " + i);

            // 5b. Verify Private Key
            KeyStore.Entry privEntry = ks.getEntry(aliasPriv, new KeyStore.PasswordProtection(pwdChars));
            assertTrue(privEntry instanceof KeyStore.PrivateKeyEntry);
            PrivateKey privateKey = ((KeyStore.PrivateKeyEntry) privEntry).getPrivateKey();
            assertEquals("RSA", privateKey.getAlgorithm(), "Client private key should be RSA");

            // 5c. Verify Certificate details (Self-Signed)
            Certificate cert = ks.getCertificate(aliasPub);
            assertNotNull(cert, "Certificate should not be null");
            assertTrue(cert instanceof X509Certificate);
            
            X509Certificate x509Cert = (X509Certificate) cert;
            x509Cert.checkValidity(); // Validates dates (throws exception if expired/not yet valid)
            
            String subjectDN = x509Cert.getSubjectX500Principal().getName();
            assertTrue(subjectDN.contains("CN=client" + i), "Certificate subject should contain CN=client" + i);
            
        }
        
        // Assert 6: Verify no extra unexpected entries exist
        // Expected total = 1 (master key) + n * 3 (priv, pub, share for each member) + nClients * 2 (priv, pub)
        int expectedTotalEntries = 1 + (n * 3) + (nClients * 2);
        assertEquals(expectedTotalEntries, ks.size(), "Keystore has an unexpected number of entries");
    }
}
