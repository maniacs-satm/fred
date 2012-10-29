package freenet.crypt;

import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;

import javax.crypto.SecretKey;

import freenet.support.HexUtil;
import freenet.support.Logger;

public class ECDHLightContext extends KeyAgreementSchemeContext {
    static { Logger.registerClass(ECDHLightContext.class); }
    private static volatile boolean logMINOR;
    
    public final ECDH ecdh;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        return sb.toString();
    }

    public ECDHLightContext(ECDH.Curves curve, SecureRandom random) {
        this.ecdh = new ECDH(curve, random);
        this.lastUsedTime = System.currentTimeMillis();
    }
    
    public ECPublicKey getPublicKey() {
        return ecdh.getPublicKey();
    }

    /*
     * Calling the following is costy; avoid
     */
    public SecretKey getHMACKey(ECPublicKey peerExponential) {
        lastUsedTime = System.currentTimeMillis();
        SecretKey sharedKey = ecdh.getAgreedSecret(peerExponential);

        if (logMINOR) {
            Logger.minor(this, "Curve in use: " + ecdh.curve.toString());
            Logger.minor(this,
                    "My exponential: " + HexUtil.bytesToHex(ecdh.getPublicKey().getEncoded()));
            Logger.minor(
                    this,
                    "Peer's exponential: "
                            + HexUtil.bytesToHex(peerExponential.getEncoded()));
            Logger.minor(this,
                    "SharedSecret = " + HexUtil.bytesToHex(sharedKey.getEncoded()));
        }

        return sharedKey;
    }

    @Override
    public byte[] getPublicKeyNetworkFormat() {
        return ecdh.getPublicKey().getEncoded();
    }
}