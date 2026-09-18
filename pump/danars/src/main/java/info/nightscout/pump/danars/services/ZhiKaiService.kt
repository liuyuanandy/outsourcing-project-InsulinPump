package info.nightscout.pump.danars.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TimeUtils
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.PumpSync.PumpState.TemporaryBasal
import app.aaps.core.interfaces.pump.defs.PumpType
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.T
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.utils.DateTimeUtil
import app.aaps.implementation.queue.CommandQueueImplementation
import dagger.android.DaggerService
import dagger.android.HasAndroidInjector
import info.nightscout.pum.ZhiKaiBLEComm
import info.nightscout.pump.common.events.EventPumpChanged
import info.nightscout.pump.common.sync.PumpDbEntryTBR
import info.nightscout.pump.common.sync.PumpSyncEntriesCreator
import info.nightscout.pump.dana.DanaPump
import info.nightscout.pump.dana.R
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.dana.comm.RecordTypes
import info.nightscout.pump.dana.events.EventDanaRNewStatus
import info.nightscout.pump.danars.ZhiKaiPlugin
import info.nightscout.pump.danars.comm.DanaRSPacket
import info.nightscout.pump.danars.comm.DanaRSPacketAPSSetEventHistory
import info.nightscout.pump.danars.comm.DanaRSPacketBasalSetCancelTemporaryBasal
import info.nightscout.pump.danars.comm.DanaRSPacketHistory
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryAlarm
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryBasal
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryBloodGlucose
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryBolus
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryCarbohydrate
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryDaily
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryPrime
import info.nightscout.pump.danars.comm.DanaRSPacketHistoryRefill
import info.nightscout.pump.danars.comm.DanaRSPacketHistorySuspend
import info.nightscout.pump.danars.comm.ZhiKaiPacketAPSBasalSetTemporaryBasal
import info.nightscout.pump.danars.comm.ZhiKaiPacketBasalSetCancelTemporaryBasal
import info.nightscout.pump.danars.comm.ZhiKaiPacketBasalSetProfileBasalRate
import info.nightscout.pump.danars.comm.ZhiKaiPacketBasalSetTemporaryBasal
import info.nightscout.pump.danars.comm.ZhiKaiPacketBlousHistory
import info.nightscout.pump.danars.comm.ZhiKaiPacketSetBlous
import info.nightscout.pump.danars.comm.ZhiKaiPacketSetBlousStop
import info.nightscout.pump.danars.comm.ZhiKaiSystus
import info.nightscout.pump.danars.util.CommandUtil
import info.nightscout.pump.danars.util.StringUtil
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import kotlin.math.min


class ZhiKaiService : DaggerService(), info.nightscout.pump.common.sync.PumpSyncEntriesCreator  {

    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var sp: SP
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var context: Context
    @Inject lateinit var danaRSPlugin: ZhiKaiPlugin
    @Inject lateinit var danaPump: ZhiKaiPump
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var bleComm: ZhiKaiBLEComm
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var dateUtil: DateUtil

    private val disposable = CompositeDisposable()
    private val mBinder: IBinder = LocalBinder()
    private var lastApproachingDailyLimit: Long = 0

    override fun onCreate() {
        super.onCreate()
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ stopSelf() }, fabricPrivacy::logException)
    }

    override fun onDestroy() {
        disposable.clear()
        super.onDestroy()
    }

    val isConnected: Boolean
        get() = bleComm.isConnected

    val isConnecting: Boolean
        get() = bleComm.isConnecting

    fun connect(from: String, address: String):Boolean{
        return bleComm.connect(from, address)
    }

    fun stopConnecting() {
        bleComm.stopConnecting()
    }

    fun disconnect(from: String) {
        bleComm.disconnect(from)
    }

    fun sendMessage(message: DanaRSPacket) {
        Log.e("----------->","DanaRSPacket message.friendlyName:"+message.friendlyName+" classname = "+message.javaClass.simpleName)
        bleComm.sendMessage(message)
    }

    fun readPumpStatus() {
        Log.e("----------------->","ZhiKaiService readPumpStatus")
        try {
            if (!bleComm.isConnected) return
            sendMessage(ZhiKaiSystus(injector))
            sendMessage(ZhiKaiPacketBlousHistory(injector))
            danaPump.lastConnection = System.currentTimeMillis()
            rxBus.send(EventDanaRNewStatus())

        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPCOMM, "Unhandled exception", e)
        }
        aapsLogger.debug(LTag.PUMPCOMM, "Pump status loaded")
    }

    fun loadEvents(): PumpEnactResult {
        Log.e("----------------->","ZhiKaiService loadEvents")
        if (!danaRSPlugin.isInitialized()) {
            val result = PumpEnactResult(injector).success(false)
            result.comment = "pump not initialized"
            return result
        }
        SystemClock.sleep(1000)
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpstatus)))
        sendMessage(ZhiKaiSystus(injector))
        danaPump.lastConnection = System.currentTimeMillis()
        return PumpEnactResult(injector).success(true)
    }

    fun setUserSettings(): PumpEnactResult {
//        val message = DanaRSPacketOptionSetUserOption(injector)
//        sendMessage(message)
//        return PumpEnactResult(injector).success(message.success())
        return PumpEnactResult(injector).success(true)

    }

    fun bolus(detailedBolusInfo: DetailedBolusInfo, carbs: Int, carbTime: Long, t: EventOverviewBolusProgress.Treatment): Boolean {
        Log.e("----------------->","ZhiKaiService bolus")
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.startingbolus)))
        danaPump.bolusDone = false
        danaPump.bolusAmountToBeDelivered = detailedBolusInfo.insulin
        danaPump.bolusStopped = false
        danaPump.bolusStopForced = false
        danaPump.bolusProgressLastTimeStamp = dateUtil.now()
        danaPump.bolusingTreatment = t
        val start = ZhiKaiPacketSetBlous(injector, detailedBolusInfo.insulin,rh,rxBus)
        if (detailedBolusInfo.insulin > 0) {
            if (!danaPump.bolusStopped) {
                sendMessage(start)
            }
            var longBreakTime = (detailedBolusInfo.insulin*20+10)*1000L;//大概输注时间18s/U
            while (!danaPump.bolusStopped && !start.failed && !danaPump.bolusDone) {
                SystemClock.sleep(100)
                if (System.currentTimeMillis() - danaPump.bolusProgressLastTimeStamp > longBreakTime) { // if i didn't receive status for more than 20 sec expecting broken comm
                    danaPump.bolusStopped = true
                    danaPump.bolusStopForced = true
                    aapsLogger.debug(LTag.PUMPCOMM, "Communication stopped")
//                    bleComm.disconnect("Communication stopped")
                    break;
                }
            }
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpstatus)))
            sendMessage(ZhiKaiSystus(injector))
        }
        detailedBolusInfo.insulin = start.value
        if(detailedBolusInfo.insulin>0){
            (commandQueue as CommandQueueImplementation).persistenceLayer.insertOrUpdateBolus(detailedBolusInfo.createBolus())
        }
        danaPump.lastConnection = System.currentTimeMillis()
        return true
    }

    fun bolusStop() {
        Log.e("----------------->","ZhiKaiService bolusStop")
        aapsLogger.debug(LTag.PUMPCOMM, "bolusStop >>>>> @ " + if (danaPump.bolusingTreatment == null) "" else danaPump.bolusingTreatment?.insulin)
        val stop = ZhiKaiPacketSetBlousStop(injector)
        danaPump.bolusStopForced = true
        if (isConnected) {
            var num= 0
            while (!danaPump.bolusStopped&&!danaPump.bolusDone||num<10) {
                sendMessage(stop)
                SystemClock.sleep(500)
                num++
            }
            danaPump.bolusStopped = true
        } else {
            danaPump.bolusStopped = true
        }
    }

    fun tempBasal(percent: Int, durationInHours: Int): Boolean {
        Log.e("----------------->","ZhiKaiService tempBasal  percent="+percent+" durationInHours = "+durationInHours)
        if (!isConnected) return false
        if (danaPump.isTempBasalInProgress) {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
            sendMessage(ZhiKaiPacketBasalSetCancelTemporaryBasal(injector))
            val tbr=pumpSync.expectedPumpState().temporaryBasal
            pumpSync.syncStopTemporaryBasalWithPumpId(dateUtil.now(), dateUtil.now(), tbr!!.pumpType,tbr!!.pumpSerial)
            val tbr2 = pumpSync.expectedPumpState().temporaryBasal
            danaPump.fromTemporaryBasal(tbr2)
        }
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingtempbasal)))
        val msgTBR = ZhiKaiPacketBasalSetTemporaryBasal(injector, percent, durationInHours*60)
        sendMessage(msgTBR)
        if(!msgTBR.isReceived){
            return false;
        }
        loadEvents()
        if(msgTBR.success()){
            val tempData = PumpDbEntryTBR(danaPump.tempBasal, true, msgTBR.temporaryBasalDuration*60, PumpSync.TemporaryBasalType.NORMAL)
            addTemporaryBasalRateWithTempId(tempData,this)
            val tbr = pumpSync.expectedPumpState().temporaryBasal
            danaPump.fromTemporaryBasal(tbr)
        }
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        danaPump.lastConnection = System.currentTimeMillis()
        Log.e("-------------->","msgTBR.success() = "+msgTBR.success())
        return msgTBR.success()
    }

    fun highTempBasal(percent: Int): Boolean {
        Log.e("----------------->","ZhiKaiService highTempBasal percent="+percent)
        if (danaPump.isTempBasalInProgress) {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
            sendMessage(ZhiKaiPacketBasalSetCancelTemporaryBasal(injector))
            SystemClock.sleep(500)
            val tbr=pumpSync.expectedPumpState().temporaryBasal
            pumpSync.syncStopTemporaryBasalWithPumpId(dateUtil.now(), dateUtil.now(), tbr!!.pumpType,tbr!!.pumpSerial)
            val tbr2 = pumpSync.expectedPumpState().temporaryBasal
            danaPump.fromTemporaryBasal(tbr2)
        }
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingtempbasal)))
        val msgTBR = ZhiKaiPacketAPSBasalSetTemporaryBasal(injector, percent)
        Log.e("----------------->","ZhiKaiService highTempBasal  percent="+msgTBR.temporaryBasalRatio+";durationInHours="+msgTBR.temporaryBasalDuration)
        sendMessage(msgTBR)
        if(!msgTBR.isReceived){
            return false;
        }
        loadEvents()
        if(msgTBR.success()){
            val tempData = PumpDbEntryTBR(danaPump.tempBasal, true, msgTBR.temporaryBasalDuration*60, PumpSync.TemporaryBasalType.NORMAL)
            addTemporaryBasalRateWithTempId(tempData,this)
            val tbr = pumpSync.expectedPumpState().temporaryBasal
            danaPump.fromTemporaryBasal(tbr)
        }
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        danaPump.lastConnection = System.currentTimeMillis()
        Log.e("-------------->","msgTBR.success() = "+msgTBR.success())
        return msgTBR.success()
    }

    fun tempBasalShortDuration(percent: Int, durationInMinutes: Int): Boolean {
        Log.e("----------------->","ZhiKaiService tempBasalShortDuration percent="+percent)
        if (danaPump.isTempBasalInProgress) {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
            sendMessage(ZhiKaiPacketBasalSetCancelTemporaryBasal(injector))
            SystemClock.sleep(500)
            val tbr=pumpSync.expectedPumpState().temporaryBasal
            pumpSync.syncStopTemporaryBasalWithPumpId(dateUtil.now(), dateUtil.now(), tbr!!.pumpType,tbr!!.pumpSerial)
            val tbr2 = pumpSync.expectedPumpState().temporaryBasal
            danaPump.fromTemporaryBasal(tbr2)
        }
        if (durationInMinutes != 15 && durationInMinutes != 30) {
            aapsLogger.error(LTag.PUMPCOMM, "Wrong duration param")
            return false
        }
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingtempbasal)))
        val msgTBR = ZhiKaiPacketAPSBasalSetTemporaryBasal(injector, percent)
        sendMessage(msgTBR)
        if(!msgTBR.isReceived){
            return false;
        }
        loadEvents()
        if(msgTBR.success()){
            val tempData = PumpDbEntryTBR(danaPump.tempBasal, true, msgTBR.temporaryBasalDuration*60, PumpSync.TemporaryBasalType.NORMAL)
            addTemporaryBasalRateWithTempId(tempData,this)
            val tbr = pumpSync.expectedPumpState().temporaryBasal
            aapsLogger.debug(LTag.PUMPCOMM, "Expected TBR found: $tbr")
            danaPump.fromTemporaryBasal(tbr)
        }
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        danaPump.lastConnection = System.currentTimeMillis()
        Log.e("-------------->","msgTBR.success() = "+msgTBR.success())
        return msgTBR.success()
    }
    fun addTemporaryBasalRateWithTempId(temporaryBasal: PumpDbEntryTBR, creator: PumpSyncEntriesCreator): Boolean {
        val timeNow: Long = System.currentTimeMillis()
        val temporaryId = creator.generateTempId(timeNow)
        Log.e("---------->","temporaryId = $temporaryId")
        val response = pumpSync.addTemporaryBasalWithTempId(
            timeNow,
            temporaryBasal.rate,
            (temporaryBasal.durationInSeconds * 1000L),
            temporaryBasal.isAbsolute,
            temporaryId,
            temporaryBasal.tbrType,
            creator.model(),
            creator.serialNumber()
        )
        return response
    }

    fun tempBasalStop(): Boolean {
        Log.e("----------------->","ZhiKaiService tempBasalStop")
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
        val msgCancel = ZhiKaiPacketBasalSetCancelTemporaryBasal(injector)
        sendMessage(msgCancel)
        if(!msgCancel.isReceived||msgCancel.failed){
            return false;
        }
        loadEvents()
        val tbr = pumpSync.expectedPumpState().temporaryBasal
        try {
            pumpSync.syncStopTemporaryBasalWithPumpId(dateUtil.now(), dateUtil.now(), tbr!!.pumpType,tbr.pumpSerial)
            val tbr2 = pumpSync.expectedPumpState().temporaryBasal
            danaPump.fromTemporaryBasal(tbr2)
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
            return msgCancel.success()
            return true
        }catch (_:Exception){
            return false
        }
    }
    fun extendedBolus(insulin: Double, durationInHalfHours: Int): Boolean {
        Log.e("----------------->","ZhiKaiService extendedBolus")
//        if (!isConnected) return false
//        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingextendedbolus)))
//        val msgExtended = DanaRSPacketBolusSetExtendedBolus(injector, insulin, durationInHalfHours)
//        sendMessage(msgExtended)
//        SystemClock.sleep(200)
//        loadEvents()
//        SystemClock.sleep(4500)
//        val eb = pumpSync.expectedPumpState().extendedBolus
//        danaPump.fromExtendedBolus(eb)
//        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
//        return msgExtended.success()
        return true

    }

    fun extendedBolusStop(): Boolean {
        Log.e("----------------->","ZhiKaiService extendedBolusStop")
//        if (!isConnected) return false
//        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingextendedbolus)))
//        val msgStop = DanaRSPacketBolusSetExtendedBolusCancel(injector)
//        sendMessage(msgStop)
//        loadEvents()
//        SystemClock.sleep(4500)
//        val eb = pumpSync.expectedPumpState().extendedBolus
//        danaPump.fromExtendedBolus(eb)
//        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
//        return msgStop.success()
        return true

    }

    fun updateBasalsInPump(profile: Profile): Boolean {
        Log.e("----------------->","ZhiKaiService updateBasalsInPump")
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.updatingbasalrates)))
        val basal = danaPump.buildZhiKaiProfileRecord(profile)
        val msgSet = ZhiKaiPacketBasalSetProfileBasalRate(injector, 0, basal)
        sendMessage(msgSet)
        readPumpStatus()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return msgSet.success()
        return true

    }

    fun loadHistory(type: Byte): PumpEnactResult {
        Log.e("----------------->","ZhiKaiService loadHistory")

        val result = PumpEnactResult(injector)
        if (!isConnected) return result
        var msg: DanaRSPacketHistory? = null
        when (type) {
            RecordTypes.RECORD_TYPE_ALARM     -> msg = DanaRSPacketHistoryAlarm(injector)
            RecordTypes.RECORD_TYPE_PRIME     -> msg = DanaRSPacketHistoryPrime(injector)
            RecordTypes.RECORD_TYPE_BASALHOUR -> msg = DanaRSPacketHistoryBasal(injector)
            RecordTypes.RECORD_TYPE_BOLUS     -> msg = DanaRSPacketHistoryBolus(injector)
            RecordTypes.RECORD_TYPE_CARBO     -> msg = DanaRSPacketHistoryCarbohydrate(injector)
            RecordTypes.RECORD_TYPE_DAILY     -> msg = DanaRSPacketHistoryDaily(injector)
            RecordTypes.RECORD_TYPE_GLUCOSE   -> msg = DanaRSPacketHistoryBloodGlucose(injector)
            RecordTypes.RECORD_TYPE_REFILL    -> msg = DanaRSPacketHistoryRefill(injector)
            RecordTypes.RECORD_TYPE_SUSPEND   -> msg = DanaRSPacketHistorySuspend(injector)
        }
//        if (msg != null) {
//            sendMessage(DanaRSPacketGeneralSetHistoryUploadMode(injector, 1))
//            SystemClock.sleep(200)
//            sendMessage(msg)
//            while (!msg.done && isConnected) {
//                SystemClock.sleep(100)
//            }
//            SystemClock.sleep(200)
//            sendMessage(DanaRSPacketGeneralSetHistoryUploadMode(injector, 0))
//        }
        result.success = msg?.success() ?: false
        return result
    }

    inner class LocalBinder : Binder() {

        val serviceInstance: ZhiKaiService
            get() = this@ZhiKaiService
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    private fun waitForWholeMinute() {
        while (true) {
            val time = dateUtil.now()
            val timeToWholeMinute = 60000 - time % 60000
            if (timeToWholeMinute > 59800 || timeToWholeMinute < 300) break
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.waitingfortimesynchronization, (timeToWholeMinute / 1000).toInt())))
            SystemClock.sleep(min(timeToWholeMinute, 100))
        }
    }
    fun isMainThread(): Boolean {
        return Looper.getMainLooper() == Looper.myLooper()
    }

    override fun generateTempId(objectA: Any): Long {
        val timestamp: Long = objectA as Long
        return DateTimeUtil.toATechDate(timestamp)
    }

    override fun model(): PumpType {
        return PumpType.DANA_RS;
    }

    override fun serialNumber(): String {
       return danaPump.serialNumber;
    }
}