// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class VPNFacade implements Seq.Proxy {
    private final int refnum;

    private static native int __New();

    public native void close() throws Exception;

    public native void reconfigureVPN() throws Exception;

    public native boolean supportsSplitDNS();

    public String toString() {
        return "VPNFacade{}";
    }

    public native void up() throws Exception;

    static {
        Libtailscale.touch();
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    VPNFacade(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public VPNFacade() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof VPNFacade)) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }
}
