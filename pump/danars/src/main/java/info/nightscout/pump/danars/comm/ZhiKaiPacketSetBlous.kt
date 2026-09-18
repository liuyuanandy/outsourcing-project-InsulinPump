package info.nightscout.pump.danars.comm

import android.util.Log
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.main.constraints.ConstraintObject
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.danars.util.StringUtil
import javax.inject.Inject
import kotlin.math.min

class ZhiKaiPacketSetBlous(
    injector: HasAndroidInjector,
    private var amount: Double = 0.0,
    var rh: ResourceHelper?,
    var rxBus: RxBus?
) : DanaRSPacket(injector) {

    @Inject
    lateinit var danaPump: ZhiKaiPump


    @Inject
    lateinit var constraintChecker: ConstraintsChecker
    var percent = 0
    var value = 0.0
    var timeLastReceiedData:Long = 0 //收到上一条数据的时间
    var setblousStopMessage:ZhiKaiPacketSetBlousStop?=null

    init {
        opCode = BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS
        amount = constraintChecker.applyBolusConstraints(ConstraintObject(amount, aapsLogger)).value()
    }
    override fun handleMessage(data: ByteArray) {
        Log.e("---------->","ZhiKaiPacketSetBlous handleMessage")
        danaPump.bolusStartErrorCode = intFromBuff(data, 4, 2)
        danaPump.bolusStartErrorCodeDes = StringUtil.bytesToHexString(data).substring(8,12)
        Log.e("---------->","ZhiKaiPacketSetBlous danaPump.bolusStartErrorCodeDes="+danaPump.bolusStartErrorCodeDes)
        timeLastReceiedData=System.currentTimeMillis();
        if(danaPump.bolusStartErrorCodeDes.equals("A0AA")){//输注进度返回
            failed = false
            aapsLogger.debug(LTag.PUMPCOMM, "Result OK")
            var dataStr = StringUtil.bytesToHexString(data).substring(12,16);
            value = (StringUtil.hexStringToInt(dataStr.substring(2,4))*256+StringUtil.hexStringToInt(dataStr.substring(0,2)))*0.025
            danaPump.bolusProgressLastTimeStamp = System.currentTimeMillis()
            val bolusingEvent = EventOverviewBolusProgress
            bolusingEvent.status = rh!!.gs(app.aaps.core.ui.R.string.bolus_delivering, value)
            bolusingEvent.t = danaPump.bolusingTreatment
            bolusingEvent.percent = min((value / danaPump.bolusAmountToBeDelivered * 100).toInt(), 100)
            percent = min((value / danaPump.bolusAmountToBeDelivered * 100).toInt(), 100);
            Log.e("------------>","bolusingEvent.percent="+bolusingEvent.percent)
            aapsLogger.debug(LTag.PUMPCOMM, "Delivered insulin so far: $value")
            rxBus!!.send(bolusingEvent)
        }else{
            failed = true;
            if(danaPump.bolusStartErrorCodeDes.equals("55AA")){
                aapsLogger.error("Result Error: 接收正确并同意执行指令")
                if(setblousStopMessage!=null){
                    failed = true
                    stopBlous()
                }else{
                    failed = false;
                }
            }else if(danaPump.bolusStartErrorCodeDes.equals("5AAA")){
                aapsLogger.error("Result Error: 接收错误，手机端需重新发送指令")
                failed = true
            }else if(danaPump.bolusStartErrorCodeDes.equals("A5AA")){
                aapsLogger.error("Result Error: 用户拒绝执行命令")
                if(setblousStopMessage!=null){
                    failed = false;
                    stopBlous()
                }else{
                    failed = true
                }
            }else if(danaPump.bolusStartErrorCodeDes.equals("A0AA")){//停止指令禁止执行
                if(setblousStopMessage!=null){
                    failed = false;
                    stopBlous()
                }else{
                    failed = true
                }
            }else if(danaPump.bolusStartErrorCodeDes.equals("AAAA")){

                failed = false
                var dataStr = StringUtil.bytesToHexString(data).substring(12,16);
                value = (StringUtil.hexStringToInt(dataStr.substring(2,4))*256+StringUtil.hexStringToInt(dataStr.substring(0,2)))*0.025
                danaPump.bolusProgressLastTimeStamp = System.currentTimeMillis()
                val bolusingEvent = EventOverviewBolusProgress
                bolusingEvent.status = rh!!.gs(app.aaps.core.ui.R.string.bolus_delivering, value)
                bolusingEvent.t = danaPump.bolusingTreatment
                bolusingEvent.percent = min((value / danaPump.bolusAmountToBeDelivered * 100).toInt(), 100)
                Log.e("------------>","bolusingEvent.value="+value)
                Log.e("------------>","bolusingEvent.percent="+bolusingEvent.percent)
                aapsLogger.debug(LTag.PUMPCOMM, "Delivered insulin so far: $value")
                rxBus!!.send(bolusingEvent)
                danaPump.bolusDone = true
                danaPump.lastBolusAmount = value
                danaPump.lastBolusTime  = System.currentTimeMillis();
                danaPump.bolusStopped = true;
            }
        }
    }

    fun stopBlous(){
        val bolusingEvent = EventOverviewBolusProgress
        danaPump.bolusStopped = true
        if (!danaPump.bolusStopForced) {
            // delivery ended without user intervention
            danaPump.bolusingTreatment?.insulin = danaPump.bolusAmountToBeDelivered
            bolusingEvent.status = rh!!.gs(info.nightscout.pump.dana.R.string.overview_bolusprogress_delivered)
            bolusingEvent.percent = 100
        } else {
//            bolusingEvent.status = rh!!.gs(app.aaps.core.ui.R.string.bolus_delivering, value)
//            bolusingEvent.t = danaPump.bolusingTreatment
//            bolusingEvent.percent = percent
            bolusingEvent.status = rh!!.gs(info.nightscout.pump.dana.R.string.overview_bolusprogress_stoped)
        }
        rxBus!!.send(bolusingEvent)
    }
    override val friendlyName: String = "BOLUS__SET_STEP_BOLUS"
    fun getAmount():Double{
        return amount
    }
}