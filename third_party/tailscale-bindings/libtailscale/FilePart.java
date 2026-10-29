// Generated gomobile JNI bindings recovered from the pinned reference SDK.
// Native SDK: Tailscale (BSD-3-Clause); gomobile glue: Go Authors (BSD-3-Clause).
// See third_party/tailscale-bindings/README.md for provenance.
package libtailscale;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
public final class FilePart implements Seq.Proxy {
    private final int refnum;

    private static native int __New();

    public final native InputStream getBody();

    public final native long getContentLength();

    public final native String getContentType();

    public final native String getFilename();

    public final native void setBody(InputStream inputStream);

    public final native void setContentLength(long j);

    public final native void setContentType(String str);

    public final native void setFilename(String str);

    static {
        Libtailscale.touch();
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    FilePart(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public FilePart() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof FilePart)) {
            return false;
        }
        FilePart filePart = (FilePart) obj;
        if (getContentLength() != filePart.getContentLength()) {
            return false;
        }
        String filename = getFilename();
        String filename2 = filePart.getFilename();
        if (filename == null) {
            if (filename2 != null) {
                return false;
            }
        } else if (!filename.equals(filename2)) {
            return false;
        }
        InputStream body = getBody();
        InputStream body2 = filePart.getBody();
        if (body == null) {
            if (body2 != null) {
                return false;
            }
        } else if (!body.equals(body2)) {
            return false;
        }
        String contentType = getContentType();
        String contentType2 = filePart.getContentType();
        if (contentType == null) {
            return contentType2 == null;
        }
        return contentType.equals(contentType2);
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getContentLength()), getFilename(), getBody(), getContentType()});
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("FilePart{ContentLength:");
        sb.append(getContentLength()).append(",Filename:");
        sb.append(getFilename()).append(",Body:");
        sb.append(getBody()).append(",ContentType:");
        sb.append(getContentType()).append(",}");
        return sb.toString();
    }
}
