package com.smartruralbus.driver.buffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingLocation::class], version = 1, exportSchema = false)
abstract class BufferDatabase : RoomDatabase() {
    abstract fun pendingLocations(): PendingLocationDao

    companion object {
        fun create(context: Context): BufferDatabase =
            Room.databaseBuilder(context.applicationContext, BufferDatabase::class.java, "gps_buffer.db").build()
    }
}
