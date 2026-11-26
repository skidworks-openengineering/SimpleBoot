package skidworks.app.simpleboot

import android.app.Application
import com.topjohnwu.superuser.Shell

class SimpleBootApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
    }
}
