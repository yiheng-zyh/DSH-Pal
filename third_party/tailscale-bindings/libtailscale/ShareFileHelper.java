// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

/* JADX INFO: loaded from: classes.dex */
public interface ShareFileHelper {
    void deleteFile(String str) throws Exception;

    String getFileInfo(String str) throws Exception;

    String getFileURI(String str) throws Exception;

    String listFilesJSON(String str) throws Exception;

    InputStream openFileReader(String str) throws Exception;

    OutputStream openFileWriter(String str, long j) throws Exception;

    String renameFile(String str, String str2) throws Exception;
}
