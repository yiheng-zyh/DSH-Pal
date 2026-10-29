// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

/* JADX INFO: loaded from: classes.dex */
public interface Application {
    LocalAPIResponse callLocalAPI(long j, String str, String str2, InputStream inputStream) throws Exception;

    LocalAPIResponse callLocalAPIMultipart(long j, String str, String str2, FileParts fileParts) throws Exception;

    void notifyPolicyChanged();

    void setClientLoggingEnabled(boolean z);

    NotificationManager watchNotifications(long j, NotificationCallback notificationCallback);
}
