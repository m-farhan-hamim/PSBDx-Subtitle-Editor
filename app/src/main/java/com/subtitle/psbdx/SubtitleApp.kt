package com.subtitle.psbdx

import android.app.Application
import androidx.room.Room
import com.subtitle.psbdx.data.AppDatabase
import com.subtitle.psbdx.data.ProjectRepository

class SubtitleApp : Application() {
    lateinit var repository: ProjectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "subtitle_editor.db").build()
        repository = ProjectRepository(this, db.projectDao())
    }
}
