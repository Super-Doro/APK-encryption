package com.function.fixture;
public final class FixtureProvider extends android.content.ContentProvider {
    static boolean ready;
    @Override public boolean onCreate() { ready = true; return true; }
    @Override public android.database.Cursor query(android.net.Uri uri, String[] projection,
            String selection, String[] args, String sort) { return null; }
    @Override public String getType(android.net.Uri uri) { return null; }
    @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { return null; }
    @Override public int delete(android.net.Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(android.net.Uri uri, android.content.ContentValues values,
            String selection, String[] args) { return 0; }
}
