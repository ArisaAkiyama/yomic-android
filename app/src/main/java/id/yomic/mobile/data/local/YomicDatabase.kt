package id.yomic.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MangaEntity::class,
        ChapterEntity::class,
        CategoryEntity::class,
        MangaCategoryCrossRef::class,
        HistoryEntity::class,
        DailyReadingLogEntity::class,
        MangaTrackEntity::class,
        DownloadTaskEntity::class,
        ExtensionCacheEntity::class,
        AnnouncementEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class YomicDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun categoryDao(): CategoryDao
    abstract fun historyDao(): HistoryDao
    abstract fun dailyReadingLogDao(): DailyReadingLogDao
    abstract fun mangaTrackDao(): MangaTrackDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun extensionCacheDao(): ExtensionCacheDao
    abstract fun announcementDao(): AnnouncementDao

    companion object {
        const val DATABASE_NAME = "yomic_mobile.db"

        /**
         * Explicit non-destructive Room migrations registry.
         * Never use `fallbackToDestructiveMigration()` in production so user libraries are never wiped.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): YomicDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YomicDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.setForeignKeyConstraintsEnabled(true)
                        // Android's SQLiteDatabase forbids execSQL() for PRAGMAs that return a row.
                        db.query("PRAGMA busy_timeout = 10000;").use { it.moveToFirst() }
                    }
                })
                .build()
    }
}
