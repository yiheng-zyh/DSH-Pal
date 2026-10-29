// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

/* JADX INFO: loaded from: classes.dex */
public interface AppContext {
    boolean bindSocketToNetwork(int i);

    String decryptFromPref(String str) throws Exception;

    void encryptToPref(String str, String str2) throws Exception;

    String getDeviceName() throws Exception;

    String getInstallSource();

    String getInterfacesAsJson() throws Exception;

    String getOSVersion() throws Exception;

    String getPlatformDNSConfig();

    long getSDKInt() throws Exception;

    String getStateStoreKeysJSON();

    boolean getSyspolicyBooleanValue(String str) throws Exception;

    String getSyspolicyStringArrayJSONValue(String str) throws Exception;

    String getSyspolicyStringValue(String str) throws Exception;

    byte[] getUserCACertsPEM() throws Exception;

    String hardwareAttestationKeyCreate() throws Exception;

    void hardwareAttestationKeyLoad(String str) throws Exception;

    byte[] hardwareAttestationKeyPublic(String str) throws Exception;

    void hardwareAttestationKeyRelease(String str) throws Exception;

    byte[] hardwareAttestationKeySign(String str, byte[] bArr) throws Exception;

    boolean hardwareAttestationKeySupported();

    boolean isChromeOS() throws Exception;

    boolean isClientLoggingEnabled() throws Exception;

    void log(String str, String str2);

    boolean shouldUseGoogleDNSFallback();
}
