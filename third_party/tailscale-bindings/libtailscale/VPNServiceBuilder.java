// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

/* JADX INFO: loaded from: classes.dex */
public interface VPNServiceBuilder {
    void addAddress(String str, int i) throws Exception;

    void addDNSServer(String str) throws Exception;

    void addRoute(String str, int i) throws Exception;

    void addSearchDomain(String str) throws Exception;

    ParcelFileDescriptor establish() throws Exception;

    void excludeRoute(String str, int i) throws Exception;

    void setMTU(int i) throws Exception;
}
