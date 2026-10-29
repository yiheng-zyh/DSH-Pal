// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class App implements Seq.Proxy, Application {
    private final int refnum;

    private static native int __New();

    @Override // libtailscale.Application
    public native LocalAPIResponse callLocalAPI(long j, String str, String str2, InputStream inputStream) throws Exception;

    @Override // libtailscale.Application
    public native LocalAPIResponse callLocalAPIMultipart(long j, String str, String str2, FileParts fileParts) throws Exception;

    @Override // libtailscale.Application
    public native void notifyPolicyChanged();

    @Override // libtailscale.Application
    public native void setClientLoggingEnabled(boolean z);

    public String toString() {
        return "App{}";
    }

    @Override // libtailscale.Application
    public native NotificationManager watchNotifications(long j, NotificationCallback notificationCallback);

    static {
        Libtailscale.touch();
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    App(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public App() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof App)) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }
}
