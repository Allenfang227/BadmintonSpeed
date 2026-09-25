package com.badminton.speed.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史记录本地存储（SQLite）。
 */
public class HistoryDB extends SQLiteOpenHelper {

    private static final String DB_NAME = "badminton_speed.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "history";

    private static final String COL_ID    = "_id";
    private static final String COL_TIME  = "ts";
    private static final String COL_PATH  = "path";
    private static final String COL_FPS   = "fps";
    private static final String COL_MAX   = "max_speed";
    private static final String COL_AVG   = "avg_speed";
    private static final String COL_TYPE  = "hit_type";
    private static final String COL_IO    = "in_out";
    private static final String COL_JSON  = "trajectory";
    private static final String COL_ERR   = "errors";

    public HistoryDB(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_TIME + " INTEGER," +
                COL_PATH + " TEXT," +
                COL_FPS + " INTEGER," +
                COL_MAX + " REAL," +
                COL_AVG + " REAL," +
                COL_TYPE + " TEXT," +
                COL_IO + " TEXT," +
                COL_JSON + " TEXT," +
                COL_ERR + " TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public long save(DetectResult r) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues v = new ContentValues();
            v.put(COL_TIME, r.timestamp);
            v.put(COL_PATH, r.videoPath);
            v.put(COL_FPS, r.fps);
            v.put(COL_MAX, r.maxSpeed);
            v.put(COL_AVG, r.avgSpeed);
            v.put(COL_TYPE, r.hitType);
            v.put(COL_IO, r.inOut);
            v.put(COL_JSON, r.trajectoryJson);
            v.put(COL_ERR, r.errors);
            return db.insert(TABLE, null, v);
        } finally { db.close(); }
    }

    public List<DetectResult> listAll() {
        List<DetectResult> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, null, null, null, null, COL_TIME + " DESC");
            while (c != null && c.moveToNext()) {
                DetectResult r = new DetectResult();
                r.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                r.timestamp = c.getLong(c.getColumnIndexOrThrow(COL_TIME));
                r.videoPath = c.getString(c.getColumnIndexOrThrow(COL_PATH));
                r.fps = c.getInt(c.getColumnIndexOrThrow(COL_FPS));
                r.maxSpeed = c.getDouble(c.getColumnIndexOrThrow(COL_MAX));
                r.avgSpeed = c.getDouble(c.getColumnIndexOrThrow(COL_AVG));
                r.hitType = c.getString(c.getColumnIndexOrThrow(COL_TYPE));
                r.inOut = c.getString(c.getColumnIndexOrThrow(COL_IO));
                out.add(r);
            }
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return out;
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c != null && c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }
}
