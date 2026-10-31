package com.compound.finance

import android.app.Application
import com.compound.finance.data.AppDatabase
import com.compound.finance.data.Prefs
import com.compound.finance.data.Repository
import com.compound.finance.sync.CompoundSync
import com.compound.finance.work.DailyWorker
import com.compound.finance.work.Notifier

class CompoundApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var sync: CompoundSync
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, AppDatabase.get(this), Prefs(this))
        sync = CompoundSync(repo)
        Notifier.createChannels(this)
        DailyWorker.schedule(this)
    }
}
