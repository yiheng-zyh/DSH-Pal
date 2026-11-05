// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

import go.Seq;

/* JADX INFO: loaded from: classes.dex */
public abstract class Libtailscale {
    private static native void _init();

    public static native String getID();

    public static native void onDNSConfigChanged(String str);

    public static native void onGatewayChanged(String str);

    public static native void requestVPN(IPNService iPNService);

    public static native void sendLog(byte[] bArr);

    public static native void serviceDisconnect(IPNService iPNService);

    public static native void setID(String str);

    public static native void setShareFileHelper(ShareFileHelper shareFileHelper);

    public static native Application start(String str, String str2, boolean z, AppContext appContext);

    public static void touch() {
    }

    static {
        Seq.touch();
        _init();
    }

    private Libtailscale() {
    }

    private static final class proxyAppContext implements Seq.Proxy, AppContext {
        private final int refnum;

        @Override // libtailscale.AppContext
        public native boolean bindSocketToNetwork(int i);

        @Override // libtailscale.AppContext
        public native String decryptFromPref(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native void encryptToPref(String str, String str2) throws Exception;

        @Override // libtailscale.AppContext
        public native String getDeviceName() throws Exception;

        @Override // libtailscale.AppContext
        public native String getInstallSource();

        @Override // libtailscale.AppContext
        public native String getInterfacesAsJson() throws Exception;

        @Override // libtailscale.AppContext
        public native String getOSVersion() throws Exception;

        @Override // libtailscale.AppContext
        public native String getPlatformDNSConfig();

        @Override // libtailscale.AppContext
        public native long getSDKInt() throws Exception;

        @Override // libtailscale.AppContext
        public native String getStateStoreKeysJSON();

        @Override // libtailscale.AppContext
        public native boolean getSyspolicyBooleanValue(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native String getSyspolicyStringArrayJSONValue(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native String getSyspolicyStringValue(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native byte[] getUserCACertsPEM() throws Exception;

        @Override // libtailscale.AppContext
        public native String hardwareAttestationKeyCreate() throws Exception;

        @Override // libtailscale.AppContext
        public native void hardwareAttestationKeyLoad(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native byte[] hardwareAttestationKeyPublic(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native void hardwareAttestationKeyRelease(String str) throws Exception;

        @Override // libtailscale.AppContext
        public native byte[] hardwareAttestationKeySign(String str, byte[] bArr) throws Exception;

        @Override // libtailscale.AppContext
        public native boolean hardwareAttestationKeySupported();

        @Override // libtailscale.AppContext
        public native boolean isChromeOS() throws Exception;

        @Override // libtailscale.AppContext
        public native boolean isClientLoggingEnabled() throws Exception;

        @Override // libtailscale.AppContext
        public native void log(String str, String str2);

        @Override // libtailscale.AppContext
        public native boolean shouldUseGoogleDNSFallback();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyAppContext(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyApplication implements Seq.Proxy, Application {
        private final int refnum;

        @Override // libtailscale.Application
        public native LocalAPIResponse callLocalAPI(long j, String str, String str2, InputStream inputStream) throws Exception;

        @Override // libtailscale.Application
        public native LocalAPIResponse callLocalAPIMultipart(long j, String str, String str2, FileParts fileParts) throws Exception;

        @Override // libtailscale.Application
        public native void notifyPolicyChanged();

        @Override // libtailscale.Application
        public native void setClientLoggingEnabled(boolean z);

        @Override // libtailscale.Application
        public native NotificationManager watchNotifications(long j, NotificationCallback notificationCallback);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyApplication(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyFileParts implements Seq.Proxy, FileParts {
        private final int refnum;

        @Override // libtailscale.FileParts
        public native FilePart get(int i);

        @Override // libtailscale.FileParts
        public native int len();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyFileParts(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyIPNService implements Seq.Proxy, IPNService {
        private final int refnum;

        @Override // libtailscale.IPNService
        public native void close();

        @Override // libtailscale.IPNService
        public native void disconnectVPN();

        @Override // libtailscale.IPNService
        public native String id();

        @Override // libtailscale.IPNService
        public native VPNServiceBuilder newBuilder();

        @Override // libtailscale.IPNService
        public native boolean protect(int i);

        @Override // libtailscale.IPNService
        public native void updateVpnStatus(boolean z);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyIPNService(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyInputStream implements Seq.Proxy, InputStream {
        private final int refnum;

        @Override // libtailscale.InputStream
        public native void close() throws Exception;

        @Override // libtailscale.InputStream
        public native byte[] read() throws Exception;

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyInputStream(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyLocalAPIResponse implements Seq.Proxy, LocalAPIResponse {
        private final int refnum;

        @Override // libtailscale.LocalAPIResponse
        public native byte[] bodyBytes() throws Exception;

        @Override // libtailscale.LocalAPIResponse
        public native InputStream bodyInputStream();

        @Override // libtailscale.LocalAPIResponse
        public native long statusCode();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyLocalAPIResponse(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyNotificationCallback implements Seq.Proxy, NotificationCallback {
        private final int refnum;

        @Override // libtailscale.NotificationCallback
        public native void onNotify(byte[] bArr) throws Exception;

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyNotificationCallback(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyNotificationManager implements Seq.Proxy, NotificationManager {
        private final int refnum;

        @Override // libtailscale.NotificationManager
        public native void stop();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyNotificationManager(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyOutputStream implements Seq.Proxy, OutputStream {
        private final int refnum;

        @Override // libtailscale.OutputStream
        public native void close() throws Exception;

        @Override // libtailscale.OutputStream
        public native long write(byte[] bArr) throws Exception;

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyOutputStream(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyParcelFileDescriptor implements Seq.Proxy, ParcelFileDescriptor {
        private final int refnum;

        @Override // libtailscale.ParcelFileDescriptor
        public native int detach() throws Exception;

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyParcelFileDescriptor(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyShareFileHelper implements Seq.Proxy, ShareFileHelper {
        private final int refnum;

        @Override // libtailscale.ShareFileHelper
        public native void deleteFile(String str) throws Exception;

        @Override // libtailscale.ShareFileHelper
        public native String getFileInfo(String str) throws Exception;

        @Override // libtailscale.ShareFileHelper
        public native String getFileURI(String str) throws Exception;

        @Override // libtailscale.ShareFileHelper
        public native String listFilesJSON(String str) throws Exception;

        @Override // libtailscale.ShareFileHelper
        public native InputStream openFileReader(String str) throws Exception;

        @Override // libtailscale.ShareFileHelper
        public native OutputStream openFileWriter(String str, long j) throws Exception;

        @Override // libtailscale.ShareFileHelper
        public native String renameFile(String str, String str2) throws Exception;

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyShareFileHelper(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    private static final class proxyVPNServiceBuilder implements Seq.Proxy, VPNServiceBuilder {
        private final int refnum;

        @Override // libtailscale.VPNServiceBuilder
        public native void addAddress(String str, int i) throws Exception;

        @Override // libtailscale.VPNServiceBuilder
        public native void addDNSServer(String str) throws Exception;

        @Override // libtailscale.VPNServiceBuilder
        public native void addRoute(String str, int i) throws Exception;

        @Override // libtailscale.VPNServiceBuilder
        public native void addSearchDomain(String str) throws Exception;

        @Override // libtailscale.VPNServiceBuilder
        public native ParcelFileDescriptor establish() throws Exception;

        @Override // libtailscale.VPNServiceBuilder
        public native void excludeRoute(String str, int i) throws Exception;

        @Override // libtailscale.VPNServiceBuilder
        public native void setMTU(int i) throws Exception;

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyVPNServiceBuilder(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }
}
