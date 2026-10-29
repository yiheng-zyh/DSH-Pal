// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class Response implements Seq.Proxy, LocalAPIResponse {
    private final int refnum;

    private static native int __New();

    @Override // libtailscale.LocalAPIResponse
    public native byte[] bodyBytes() throws Exception;

    @Override // libtailscale.LocalAPIResponse
    public native InputStream bodyInputStream();

    public native void flush();

    @Override // libtailscale.LocalAPIResponse
    public native long statusCode();

    public String toString() {
        return "Response{}";
    }

    public native long write(byte[] bArr) throws Exception;

    public native void writeHeader(long j);

    static {
        Libtailscale.touch();
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    Response(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public Response() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Response)) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }
}
