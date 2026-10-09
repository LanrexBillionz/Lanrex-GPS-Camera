package com.lanrex.sitecam.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [StampItem::class, AddressCacheEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stampItems(): StampItemDao
    abstract fun addressCache(): AddressCacheDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sitecam.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
