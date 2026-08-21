package utils;

import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;

import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import logger.Logger;

public class ThreshSigSetup {
    public static void main(String[] args) throws Exception {
        int nClients = 3;

        // HotStuff configuration: n = 4, threshold =3 
        int n = 4;
        int t = 3; 
        int keySize = 2048;         // 2048-bit keys
        String password = "changeit";
        String fileName = "all_members.p12";

        generate(nClients, n, t, keySize, password, fileName);
        Logger.info(null, "Successfully generated " + fileName + " with " + n + " members.");
    }

    public static void generate(int nClients, int n, int t, int keySize, String password, String fileName) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        char[] pwd = password.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, pwd);

        // Initialize the Dealer with the key size
        Dealer dealer = new Dealer(keySize);
        
        // Generate the keys using the threshold (k) and total members (l)
        dealer.generateKeys(t, n);
        
        // Retrieve the generated keys
        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] privateShares = dealer.getShares();

        // Store Master Public Key (Required for QC verification)
        ks.setEntry("master_public_key",
                new KeyStore.SecretKeyEntry(new SecretKeySpec(Utils.serialize(groupKey), "AES")),
                new KeyStore.PasswordProtection(pwd));

        for (int i = 1; i <= n; i++) {
            String alias = "member" + i;

            // Generate RSA
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            
            X509Certificate cert = createSelfSignedCert(kp, alias);

            // Save RSA Private Key and Public Cert
            ks.setKeyEntry(alias + "_priv", kp.getPrivate(), pwd, new Certificate[]{cert});
            ks.setCertificateEntry(alias + "_pub", cert);
            
            // Save Member's threshold share
            KeyShare share = privateShares[i - 1];
            ks.setEntry(alias + "_threshold_share",
                    new KeyStore.SecretKeyEntry(new SecretKeySpec(Utils.serialize(share), "AES")),
                    new KeyStore.PasswordProtection(pwd));
        }

        for (int i = 10; i < nClients + 10; i++) {
            String alias = "client" + i;

            // Generate RSA Keys
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            
            X509Certificate cert = createSelfSignedCert(kp, alias);

            // Save RSA Private Key and Public Cert
            ks.setKeyEntry(alias + "_priv", kp.getPrivate(), pwd, new Certificate[]{cert});
            ks.setCertificateEntry(alias + "_pub", cert);
        }
        
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            ks.store(fos, pwd);
        }
    }

    private static X509Certificate createSelfSignedCert(KeyPair kp, String id) throws Exception {
        long now = System.currentTimeMillis();
        X500Name dnName = new X500Name("CN=" + id);
        BigInteger serial = BigInteger.valueOf(now);
        Date from = new Date(now);
        Date to = new Date(now + 1000L * 60 * 60 * 24 * 365); // 1 year

        X509v3CertificateBuilder cb = new JcaX509v3CertificateBuilder(
                dnName, serial, from, to, dnName, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(cb.build(signer));
    }
}