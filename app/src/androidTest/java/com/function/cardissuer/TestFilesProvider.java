package com.function.cardissuer;

public final class TestFilesProvider extends android.content.ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public android.database.Cursor query(android.net.Uri uri, String[] projection,
            String selection, String[] args, String sort) {
        android.database.MatrixCursor cursor = new android.database.MatrixCursor(
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment()});
        return cursor;
    }
    @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode)
            throws java.io.FileNotFoundException {
        try {
            if (uri.getLastPathSegment().startsWith("export")) {
                java.io.File file = new java.io.File(getContext().getCacheDir(), uri.getLastPathSegment());
                return android.os.ParcelFileDescriptor.open(file, mode.equals("r")
                        ? android.os.ParcelFileDescriptor.MODE_READ_ONLY
                        : android.os.ParcelFileDescriptor.MODE_READ_WRITE | android.os.ParcelFileDescriptor.MODE_CREATE
                                | android.os.ParcelFileDescriptor.MODE_TRUNCATE);
            }
            android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (java.io.OutputStream output = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (uri.getLastPathSegment().endsWith(".sh")) {
                        output.write("#!/system/bin/sh\necho UI_SCRIPT_OK\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        try (java.io.InputStream input = getContext().getAssets().open("fixture.apk")) {
                            byte[] buffer = new byte[8192];
                            int size;
                            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                        }
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
            }).start();
            return pipe[0];
        } catch (java.io.IOException error) { throw new java.io.FileNotFoundException(error.getMessage()); }
    }
    @Override public String getType(android.net.Uri uri) { return "application/octet-stream"; }
    @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { return null; }
    @Override public int delete(android.net.Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(android.net.Uri uri, android.content.ContentValues values, String selection, String[] args) { return 0; }
}
