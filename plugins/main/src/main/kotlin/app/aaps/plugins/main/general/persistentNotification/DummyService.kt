package app.aaps.plugins.main.general.persistentNotification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.os.Binder
import android.os.Build
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

/**
 * Keeps AndroidAPS in foreground state, so it won't be terminated by Android nor get restricted by the background execution limits
 */
class DummyService : DaggerService() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var notificationHolder: NotificationHolder

    private val disposable = CompositeDisposable()

    inner class LocalBinder : Binder() {

        fun getService(): DummyService = this@DummyService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        try {
            aapsLogger.debug("Starting DummyService with ID ${notificationHolder.notificationID} notification ${notificationHolder.notification}")
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                //从 Android 12（API 级别 31）开始，所有前台服务都必须指定一个 foregroundServiceType
                startForeground(notificationHolder.notificationID, notificationHolder.notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC);
//                ()
            }else{
                startForeground(notificationHolder.notificationID, notificationHolder.notification)
            }
        } catch (e: Exception) {
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                //从 Android 12（API 级别 31）开始，所有前台服务都必须指定一个 foregroundServiceType
                startForeground(4711, Notification(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }else{
                startForeground(4711, Notification())
            }
        }
        disposable.add(
            rxBus
                .toObservable(EventAppExit::class.java)
                .observeOn(aapsSchedulers.io)
                .subscribe({
                               aapsLogger.debug(LTag.CORE, "EventAppExit received")
                               stopSelf()
                           }, fabricPrivacy::logException)
        )
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.CORE, "onDestroy")
        disposable.clear()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            aapsLogger.debug("Starting DummyService with ID ${notificationHolder.notificationID} notification ${notificationHolder.notification}")
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                //从 Android 12（API 级别 31）开始，所有前台服务都必须指定一个 foregroundServiceType
                startForeground(notificationHolder.notificationID, notificationHolder.notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC);
//                ()
            }else{
                startForeground(notificationHolder.notificationID, notificationHolder.notification)
            }
        } catch (e: Exception) {
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                //从 Android 12（API 级别 31）开始，所有前台服务都必须指定一个 foregroundServiceType
                startForeground(4711, Notification(),FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }else{
                startForeground(4711, Notification())
            }
        }
        return Service.START_STICKY
    }
}
