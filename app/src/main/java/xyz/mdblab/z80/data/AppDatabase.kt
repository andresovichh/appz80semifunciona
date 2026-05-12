package xyz.mdblab.z80.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import xyz.mdblab.z80.data.dao.VendingDao
import xyz.mdblab.z80.data.entities.Config
import xyz.mdblab.z80.data.entities.Slot
import xyz.mdblab.z80.data.entities.Transaction

@Database(entities = [Slot::class, Transaction::class, Config::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vendingDao(): VendingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vending_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
