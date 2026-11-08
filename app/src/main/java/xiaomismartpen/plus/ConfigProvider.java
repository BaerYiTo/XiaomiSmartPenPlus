package xiaomismartpen.plus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Read-only plumbing between the config UI (this app's process) and system_server, where the module
 * runs. system_server cannot read another app's SharedPreferences, so it asks here instead.
 */
public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (ConfigStore.METHOD_SNAPSHOT.equals(method)) {
            result.putString(ConfigStore.KEY_SNAPSHOT, ConfigStore.load(getContext()).serialize());
        }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }
}
