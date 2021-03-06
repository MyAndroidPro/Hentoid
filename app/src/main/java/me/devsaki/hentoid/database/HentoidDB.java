package me.devsaki.hentoid.database;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

import me.devsaki.hentoid.database.constants.AttributeTable;
import me.devsaki.hentoid.database.constants.ContentAttributeTable;
import me.devsaki.hentoid.database.constants.ContentTable;
import me.devsaki.hentoid.database.constants.ImageFileTable;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.util.AttributeMap;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.LogHelper;

/**
 * Created by DevSaki on 10/05/2015.
 * db maintenance class
 */
public class HentoidDB extends SQLiteOpenHelper {
    private static final String TAG = LogHelper.makeLogTag(HentoidDB.class);

    private static final Object locker = new Object();
    private static final int DATABASE_VERSION = 1;
    private static HentoidDB instance;

    private HentoidDB(Context context) {
        super(context, Consts.DATABASE_NAME, null, DATABASE_VERSION);
    }

    // Use this to get db instance
    public static synchronized HentoidDB getInstance(Context context) {
        // Use application context only
        if (instance == null) {
            instance = new HentoidDB(context.getApplicationContext());
        }

        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(ContentTable.CREATE_TABLE);
        db.execSQL(AttributeTable.CREATE_TABLE);
        db.execSQL(ContentAttributeTable.CREATE_TABLE);
        db.execSQL(ImageFileTable.CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        // Drop older table if it exists
        db.execSQL("DROP TABLE IF EXISTS " + ContentAttributeTable.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + AttributeTable.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + ContentTable.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + ImageFileTable.TABLE_NAME);

        // Create tables again
        onCreate(db);
    }

    public long getContentCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        long count = DatabaseUtils.queryNumEntries(db, ContentTable.TABLE_NAME);
        db.close();

        return count;
    }

    public void insertContent(Content row) {
        insertContents(new Content[]{row});
    }

    public void insertContents(Content[] rows) {
        synchronized (locker) {
            LogHelper.d(TAG, "insertContents");
            SQLiteDatabase db = null;
            SQLiteStatement statement = null;

            try {
                db = getWritableDatabase();
                statement = db.compileStatement(ContentTable.INSERT_STATEMENT);
                db.beginTransaction();

                for (Content row : rows) {
                    deleteContent(db, row);

                    statement.clearBindings();
                    statement.bindLong(1, row.getId());
                    statement.bindString(2, row.getUniqueSiteId());
                    String category = row.getCategory();

                    if (category == null) {
                        statement.bindNull(3);
                    } else {
                        statement.bindString(3, category);
                    }

                    statement.bindString(4, row.getUrl());
                    statement.bindNull(5);

                    if (row.getTitle() == null) {
                        statement.bindNull(6);
                    } else {
                        statement.bindString(6, row.getTitle());
                    }

                    statement.bindLong(7, row.getQtyPages());
                    statement.bindLong(8, row.getUploadDate());
                    statement.bindLong(9, row.getDownloadDate());
                    statement.bindLong(10, row.getStatus().getCode());

                    if (row.getCoverImageUrl() == null) {
                        statement.bindNull(11);
                    } else {
                        statement.bindString(11, row.getCoverImageUrl());
                    }

                    statement.bindLong(12, row.getSite().getCode());
                    statement.execute();

                    if (row.getImageFiles() != null) {
                        insertImageFiles(db, row);
                    }

                    List<Attribute> attributes = new ArrayList<>();
                    for (AttributeType attributeType : AttributeType.values()) {
                        if (row.getAttributes().get(attributeType) != null) {
                            attributes.addAll(row.getAttributes().get(attributeType));
                        }
                    }
                    insertAttributes(db, row, attributes);
                }
                db.setTransactionSuccessful();
                db.endTransaction();
            } finally {
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (statement != null) {
                    statement.close();
                }
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }
    }

    public void insertImageFiles(Content content) {
        synchronized (locker) {
            LogHelper.d(TAG, "insertImageFiles");
            SQLiteDatabase db = null;
            SQLiteStatement statement = null;
            SQLiteStatement statementImages = null;

            try {
                db = getWritableDatabase();
                db.beginTransaction();
                statement = db.compileStatement(ImageFileTable.INSERT_STATEMENT);
                statementImages = db.compileStatement(ImageFileTable.DELETE_STATEMENT);
                statementImages.clearBindings();
                statementImages.bindLong(1, content.getId());
                statementImages.execute();

                insertImageFiles(statement, content);
                db.setTransactionSuccessful();
                db.endTransaction();

            } finally {
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (statement != null) {
                    statement.close();
                }
                if (statementImages != null) {
                    statementImages.close();
                }
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }
    }

    private void insertAttributes(SQLiteDatabase db, Content content, List<Attribute> rows) {
        SQLiteStatement statement = null;
        SQLiteStatement statementContentAttribute = null;

        try {
            statement = db.compileStatement(AttributeTable.INSERT_STATEMENT);
            statementContentAttribute = db.compileStatement(ContentAttributeTable.INSERT_STATEMENT);

            for (Attribute row : rows) {
                statement.clearBindings();
                statement.bindLong(1, row.getId());
                statement.bindString(2, row.getUrl());
                statement.bindString(3, row.getName());
                statement.bindLong(4, row.getType().getCode());
                statement.execute();

                statementContentAttribute.clearBindings();
                statementContentAttribute.bindLong(1, content.getId());
                statementContentAttribute.bindLong(2, row.getId());
                statementContentAttribute.execute();
            }
        } finally {
            if (statement != null) {
                statement.close();
            }
            if (statementContentAttribute != null) {
                statementContentAttribute.close();
            }
        }
    }

    private void insertImageFiles(SQLiteDatabase db, Content content) {
        SQLiteStatement statement = null;
        try {
            statement = db.compileStatement(ImageFileTable.INSERT_STATEMENT);
            insertImageFiles(statement, content);
        } finally {
            if (statement != null) {
                statement.close();
            }
        }
    }

    private void insertImageFiles(SQLiteStatement statement, Content content) {
        for (ImageFile row : content.getImageFiles()) {
            statement.clearBindings();
            statement.bindLong(1, row.getId());
            statement.bindLong(2, content.getId());
            statement.bindLong(3, row.getOrder());
            statement.bindString(4, row.getUrl());
            statement.bindString(5, row.getName());
            statement.bindLong(6, row.getStatus().getCode());
            statement.execute();
        }
    }

    public Content selectContentById(int id) {
        Content result = null;
        synchronized (locker) {
            LogHelper.d(TAG, "selectContentById");
            SQLiteDatabase db = null;
            Cursor cursorContents = null;
            try {
                db = getReadableDatabase();
                cursorContents = db.rawQuery(ContentTable.SELECT_BY_CONTENT_ID,
                        new String[]{id + ""});

                // looping through all rows and adding to list
                if (cursorContents.moveToFirst()) {
                    result = populateContent(cursorContents, db);
                }
            } finally {
                if (cursorContents != null) {
                    cursorContents.close();
                }
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }

        return result;
    }

    public Content selectContentByStatus(StatusContent statusContent) {
        Content result = null;

        synchronized (locker) {
            LogHelper.d(TAG, "selectContentByStatus");

            SQLiteDatabase db = null;
            Cursor cursorContent = null;
            try {
                db = getReadableDatabase();
                cursorContent = db.rawQuery(ContentTable.SELECT_BY_STATUS,
                        new String[]{statusContent.getCode() + ""});

                if (cursorContent.moveToFirst()) {
                    result = populateContent(cursorContent, db);
                }
            } finally {
                if (cursorContent != null) {
                    cursorContent.close();
                }
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }

        return result;
    }

    public List<Content> selectContentInQueue() {
        List<Content> result = null;
        synchronized (locker) {
            LogHelper.d(TAG, "selectContentInQueue");
            SQLiteDatabase db = null;
            Cursor cursorContent = null;
            try {
                db = getReadableDatabase();
                cursorContent = db.rawQuery(ContentTable.SELECT_IN_DOWNLOAD_MANAGER,
                        new String[]{StatusContent.DOWNLOADING.getCode() + "",
                                StatusContent.PAUSED.getCode() + ""});

                result = populateResult(cursorContent, db);
            } finally {
                closeCursor(cursorContent, db);
            }
        }

        return result;
    }

    // This is a long running task, execute with AsyncTask or similar
    public List<Content> selectContentByQuery(String query, int page, int qty, boolean order) {
        String q = query;
        List<Content> result = null;

        synchronized (locker) {
            LogHelper.d(TAG, "selectContentByQuery");

            SQLiteDatabase db = null;
            Cursor cursorContent = null;
            int start = (page - 1) * qty;
            try {
                q = "%" + q + "%";
                db = getReadableDatabase();
                String sql = ContentTable.SELECT_DOWNLOADS;
                if (order) {
                    sql += ContentTable.ORDER_ALPHABETIC;
                } else {
                    sql += ContentTable.ORDER_BY_DATE;
                }
                if (qty < 0) {
                    cursorContent = db.rawQuery(sql,
                            new String[]{StatusContent.DOWNLOADED.getCode() + "",
                                    StatusContent.ERROR.getCode() + "",
                                    StatusContent.MIGRATED.getCode() + "", q, q,
                                    AttributeType.ARTIST.getCode() + "",
                                    AttributeType.TAG.getCode() + "",
                                    AttributeType.SERIE.getCode() + ""});
                } else {
                    cursorContent = db.rawQuery(sql + ContentTable.LIMIT_BY_PAGE,
                            new String[]{StatusContent.DOWNLOADED.getCode() + "",
                                    StatusContent.ERROR.getCode() + "",
                                    StatusContent.MIGRATED.getCode() + "", q, q,
                                    AttributeType.ARTIST.getCode() + "",
                                    AttributeType.TAG.getCode() + "",
                                    AttributeType.SERIE.getCode() + "", start + "", qty + ""});
                }
                result = populateResult(cursorContent, db);
            } finally {
                closeCursor(cursorContent, db);
            }
        }

        return result;
    }

    private List<Content> populateResult(Cursor cursorContent, SQLiteDatabase db) {
        List<Content> result = null;
        if (cursorContent.moveToFirst()) {
            result = new ArrayList<>();
            do {
                result.add(populateContent(cursorContent, db));
            } while (cursorContent.moveToNext());
        }

        return result;
    }

    private void closeCursor(Cursor cursorContent, SQLiteDatabase db) {
        if (cursorContent != null) {
            cursorContent.close();
        }
        LogHelper.d(TAG, "Closing db connection. Condition: "
                + (db != null && db.isOpen()));
        if (db != null && db.isOpen()) {
            db.close(); // Closing database connection
        }
    }

    private Content populateContent(Cursor cursorContent, SQLiteDatabase db) {
        Content content = new Content()
                .setUrl(cursorContent.getString(3))
                .setTitle(cursorContent.getString(4))
                .setQtyPages(cursorContent.getInt(6))
                .setUploadDate(cursorContent.getLong(7))
                .setDownloadDate(cursorContent.getLong(8))
                .setStatus(StatusContent.searchByCode(cursorContent.getInt(9)))
                .setCoverImageUrl(cursorContent.getString(10))
                .setSite(Site.searchByCode(cursorContent.getInt(11)));

        content.setImageFiles(selectImageFilesByContentId(db, content.getId()))
                .setAttributes(selectAttributesByContentId(db, content.getId()));

        return content;
    }

    private List<ImageFile> selectImageFilesByContentId(SQLiteDatabase db, int id) {
        List<ImageFile> result = null;
        Cursor cursorImageFiles = null;
        try {
            cursorImageFiles = db.rawQuery(ImageFileTable.SELECT_BY_CONTENT_ID,
                    new String[]{id + ""});

            // looping through all rows and adding to list
            if (cursorImageFiles.moveToFirst()) {
                result = new ArrayList<>();
                do {
                    result.add(new ImageFile()
                            .setOrder(cursorImageFiles.getInt(2))
                            .setStatus(StatusContent.searchByCode(cursorImageFiles.getInt(3)))
                            .setUrl(cursorImageFiles.getString(4))
                            .setName(cursorImageFiles.getString(5)));
                } while (cursorImageFiles.moveToNext());
            }
        } finally {
            if (cursorImageFiles != null) {
                cursorImageFiles.close();
            }
        }

        return result;
    }

    private AttributeMap selectAttributesByContentId(SQLiteDatabase db, int id) {
        AttributeMap result = null;
        Cursor cursorAttributes = null;
        try {
            cursorAttributes = db.rawQuery(AttributeTable.SELECT_BY_CONTENT_ID,
                    new String[]{id + ""});

            // looping through all rows and adding to list
            if (cursorAttributes.moveToFirst()) {
                result = new AttributeMap();
                do {
                    result.add(new Attribute()
                            .setUrl(cursorAttributes.getString(1))
                            .setName(cursorAttributes.getString(2))
                            .setType(AttributeType.searchByCode(cursorAttributes.getInt(3))));
                } while (cursorAttributes.moveToNext());
            }
        } finally {
            if (cursorAttributes != null) {
                cursorAttributes.close();
            }
        }

        return result;
    }

    public void updateImageFileStatus(ImageFile row) {
        synchronized (locker) {
            LogHelper.d(TAG, "updateImageFileStatus");
            SQLiteDatabase db = null;
            SQLiteStatement statement = null;
            try {
                db = getWritableDatabase();
                statement = db.compileStatement(ImageFileTable.UPDATE_IMAGE_FILE_STATUS_STATEMENT);
                db.beginTransaction();
                statement.clearBindings();
                statement.bindLong(1, row.getStatus().getCode());
                statement.bindLong(2, row.getId());
                statement.execute();
                db.setTransactionSuccessful();
                db.endTransaction();
            } finally {
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (statement != null) {
                    statement.close();
                }
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }
    }

    private void deleteContent(SQLiteDatabase db, Content content) {
        SQLiteStatement statement = null;
        SQLiteStatement statementImages = null;
        SQLiteStatement statementAttributes = null;

        try {
            statement = db.compileStatement(ContentTable.DELETE_STATEMENT);
            statementImages = db.compileStatement(ImageFileTable.DELETE_STATEMENT);
            statementAttributes = db.compileStatement(ContentAttributeTable.DELETE_STATEMENT);
            statement.clearBindings();
            statement.bindLong(1, content.getId());
            statement.execute();
            statementImages.clearBindings();
            statementImages.bindLong(1, content.getId());
            statementImages.execute();
            statementAttributes.clearBindings();
            statementAttributes.bindLong(1, content.getId());
            statementAttributes.execute();
        } finally {
            if (statement != null) {
                statement.close();
            }
            if (statementImages != null) {
                statementImages.close();
            }
            if (statementAttributes != null) {
                statementAttributes.close();
            }
        }
    }

    public void deleteContent(Content content) {
        synchronized (locker) {
            LogHelper.d(TAG, "deleteContent");
            SQLiteDatabase db = null;
            SQLiteStatement statement = null;
            SQLiteStatement statementImages = null;
            SQLiteStatement statementAttributes = null;

            try {
                db = getWritableDatabase();
                statement = db.compileStatement(ContentTable.DELETE_STATEMENT);
                statementImages = db.compileStatement(ImageFileTable.DELETE_STATEMENT);
                statementAttributes = db.compileStatement(ContentAttributeTable.DELETE_STATEMENT);
                db.beginTransaction();
                statement.clearBindings();
                statement.bindLong(1, content.getId());
                statement.execute();
                statementImages.clearBindings();
                statementImages.bindLong(1, content.getId());
                statementImages.execute();
                statementAttributes.clearBindings();
                statementAttributes.bindLong(1, content.getId());
                statementAttributes.execute();
                db.setTransactionSuccessful();
                db.endTransaction();
            } finally {
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (statement != null) {
                    statement.close();
                }
                if (statementImages != null) {
                    statementImages.close();
                }
                if (statementAttributes != null) {
                    statementAttributes.close();
                }
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }
    }

    public void updateContentStatus(Content row) {
        synchronized (locker) {
            LogHelper.d(TAG, "updateContentStatus");
            SQLiteDatabase db = null;
            SQLiteStatement statement = null;

            try {
                db = getWritableDatabase();
                statement = db.compileStatement(ContentTable
                        .UPDATE_CONTENT_DOWNLOAD_DATE_STATUS_STATEMENT);
                db.beginTransaction();
                statement.clearBindings();
                statement.bindLong(1, row.getDownloadDate());
                statement.bindLong(2, row.getStatus().getCode());
                statement.bindLong(3, row.getId());
                statement.execute();
                db.setTransactionSuccessful();
                db.endTransaction();
            } finally {
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (statement != null) {
                    statement.close();
                }
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }
    }

    public void updateContentStatus(StatusContent updateTo, StatusContent updateFrom) {
        synchronized (locker) {
            LogHelper.d(TAG, "updateContentStatus2");
            SQLiteDatabase db = null;
            SQLiteStatement statement = null;

            try {
                db = getWritableDatabase();
                statement = db.compileStatement(ContentTable.UPDATE_CONTENT_STATUS_STATEMENT);
                db.beginTransaction();
                statement.clearBindings();
                statement.bindLong(1, updateTo.getCode());
                statement.bindLong(2, updateFrom.getCode());
                statement.execute();
                db.setTransactionSuccessful();
                db.endTransaction();
            } finally {
                LogHelper.d(TAG, "Closing db connection. Condition: "
                        + (db != null && db.isOpen()));
                if (statement != null) {
                    statement.close();
                }
                if (db != null && db.isOpen()) {
                    db.close(); // Closing database connection
                }
            }
        }
    }
}
