package info.nightscout.pump.danars.comm

import android.util.Log
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.dana.DanaPump
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.danars.util.StringUtil
import javax.inject.Inject

class ZhiKaiPacketSetBlousStop (
injector: HasAndroidInjector
) : DanaRSPacket(injector) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var danaPump: ZhiKaiPump

    init {
        opCode = BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS_STOP
    }

    override fun handleMessage(data: ByteArray) {
        Log.e("------------>","ZhiKaiPacketSetBlousStop handleMessage")
        danaPump.bolusStartErrorCode = intFromBuff(data, 4, 2)
        danaPump.bolusStartErrorCodeDes = StringUtil.bytesToHexString(data).substring(8,12)
        @Suppress("LiftReturnOrAssignment")
        if(danaPump.bolusStartErrorCodeDes.equals("55AA")){
            aapsLogger.error("Result Error: 接收正确并同意执行指令")
            failed = false
        }else {
            aapsLogger.error("Result Error: $danaPump.bolusStartErrorCodeDes")
            failed = true
        }
        val bolusingEvent = EventOverviewBolusProgress
        danaPump.bolusStopped = true
        if (!danaPump.bolusStopForced) {
            // delivery ended without user intervention
            danaPump.bolusingTreatment?.insulin = danaPump.bolusAmountToBeDelivered
            bolusingEvent.status = rh.gs(info.nightscout.pump.dana.R.string.overview_bolusprogress_delivered)
            bolusingEvent.percent = 100
        } else {
            bolusingEvent.status = rh.gs(info.nightscout.pump.dana.R.string.overview_bolusprogress_stoped)
        }
        rxBus.send(bolusingEvent)
    }

    override val friendlyName: String = "BOLUS__SET_STEP_BOLUS_STOP"
}