package app.einkradio

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Radio.init(this)
        Radio.autoLogin()
    }
}
