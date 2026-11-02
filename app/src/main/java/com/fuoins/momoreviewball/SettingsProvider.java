package com.fuoins.momoreviewball;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 只读配置提供者。悬浮球运行在墨墨背单词进程里，通过它拿到本模块的设置。
 * 允许写入位置/锁定状态，方便在复习页拖动后持久化。
 */
public class SettingsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"json"});
        cursor.addRow(new Object[]{Store.load(getContext()).toJson()});
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) return null;

        if ("get".equals(method)) {
            Bundle result = new Bundle();
            result.putString("json", Store.load(context).toJson());
            return result;
        }

        if ("setPosition".equals(method) && extras != null) {
            Config config = Store.load(context);
            config.xRatio = Config.clamp(extras.getFloat("x", config.xRatio), 0f, 1f);
            config.yRatio = Config.clamp(extras.getFloat("y", config.yRatio), 0f, 1f);
            Store.save(context, config);
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            return result;
        }

        if ("setLocked".equals(method) && extras != null) {
            Config config = Store.load(context);
            config.locked = extras.getBoolean("locked", config.locked);
            Store.save(context, config);
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            return result;
        }

        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
