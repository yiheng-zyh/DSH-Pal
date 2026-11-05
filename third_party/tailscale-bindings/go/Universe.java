// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package go;

/* JADX INFO: loaded from: classes.dex */
public abstract class Universe {
    private static native void _init();

    public static void touch() {
    }

    static {
        Seq.touch();
        _init();
    }

    private Universe() {
    }

    private static final class proxyerror extends Exception implements Seq.Proxy, error {
        private final int refnum;

        @Override // go.error
        public native String error();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyerror(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // java.lang.Throwable
        public String getMessage() {
            return error();
        }
    }
}
