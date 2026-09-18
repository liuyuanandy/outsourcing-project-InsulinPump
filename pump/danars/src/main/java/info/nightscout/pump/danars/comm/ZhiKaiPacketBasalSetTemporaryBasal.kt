package info.nightscout.pump.danars.comm

import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.danars.util.StringUtil

class ZhiKaiPacketBasalSetTemporaryBasal (
    injector: HasAndroidInjector,
    public var temporaryBasalRatio: Int = 0,
    public var temporaryBasalDuration: Int = 0
) : DanaRSPacket(injector) {

    init {
        opCode = BleEncryption.ZHIKAI_PACKET_OPCODE_BASAL_SET_TEMPORARY_BASAL
        aapsLogger.debug(LTag.PUMPCOMM, "Setting temporary basal of $temporaryBasalRatio% for $temporaryBasalDuration mins")
    }
    override fun handleMessage(data: ByteArray) {
        val result = StringUtil.bytesToHexString(data)
        @Suppress("LiftReturnOrAssignment")
        if (!result.equals("AA0A00A155AA0000EC39")) {
            failed = true
            aapsLogger.error("Result Error: $result")
        } else {
            failed = false
            aapsLogger.debug(LTag.PUMPCOMM, "Result OK")
        }
    }

    override val friendlyName: String = "BASAL__SET_TEMPORARY_BASAL"

}