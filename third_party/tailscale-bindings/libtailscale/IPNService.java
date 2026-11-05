// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

/* JADX INFO: loaded from: classes.dex */
public interface IPNService {
    void close();

    void disconnectVPN();

    String id();

    VPNServiceBuilder newBuilder();

    boolean protect(int i);

    void updateVpnStatus(boolean z);
}
