package org.telegram.messenger.telepremium.cloud;
import java.io.File;
import java.io.IOException;
/** Deletes only named app-private cache trees, without following symbolic links. */
public final class TelePremiumCloudResetFiles {
    private TelePremiumCloudResetFiles() { }
    public static void remove(File base, String relative) throws IOException {
        if (base == null) return;
        if (relative == null || new File(relative).isAbsolute()) throw new IOException("Unsafe reset path");
        String[] parts = relative.split("/", -1);
        File cursor = base.getCanonicalFile();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe reset path");
            cursor = new File(cursor, part);
            if (i + 1 < parts.length && !cursor.getCanonicalFile().equals(cursor.getAbsoluteFile()))
                throw new IOException("Linked reset parent");
        }
        removeTree(cursor);
    }
    private static void removeTree(File file) throws IOException {
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) {
            if (!file.delete()) throw new IOException("Cannot remove reset cache link");
            return;
        }
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list reset cache");
            for (File child : children) removeTree(child);
        }
        if (!file.delete()) throw new IOException("Cannot remove reset cache");
    }
}
