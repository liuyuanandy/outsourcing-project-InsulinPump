package app.aaps.plugins.constraints.signatureVerifier

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.constraints.R
import dagger.android.HasAndroidInjector
import org.spongycastle.util.encoders.Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidAPS is meant to be build by the user.
 * In case someone decides to leak a ready-to-use APK nonetheless, we can still disable it.
 * Self-compiled APKs with privately held certificates cannot and will not be disabled.
 */
@Singleton
class SignatureVerifierPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val sp: SP,
    private val context: Context,
    private val uiInteraction: UiInteraction
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.CONSTRAINTS)
        .neverVisible(true)
        .alwaysEnabled(true)
        .showInList(false)
        .pluginName(R.string.signature_verifier),
    aapsLogger, rh, injector
), PluginConstraints {

    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private val REVOKED_CERTS_URL = "https://raw.githubusercontent.com/nightscout/AndroidAPS/master/app/src/main/assets/revoked_certs.txt"
    private val UPDATE_INTERVAL = TimeUnit.DAYS.toMillis(1)

    private val lock: Any = arrayOfNulls<Any>(0)
    private var revokedCertsFile: File? = null
    private var revokedCerts: List<ByteArray>? = null
    override fun onStart() {
        super.onStart()
        revokedCertsFile = File(context.filesDir, "revoked_certs.txt")
        handler.post {
            loadLocalRevokedCerts()
            if (shouldDownloadCerts()) {
                try {
                    downloadAndSaveRevokedCerts()
                } catch (e: IOException) {
                    aapsLogger.error("Could not download revoked certs", e)
                }
            }
            if (hasIllegalSignature()) showNotification()
        }
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun isLoopInvocationAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (hasIllegalSignature()) {
            showNotification()
            value.set(false)
        }
        if (shouldDownloadCerts()) {
            handler.post {
                try {
                    downloadAndSaveRevokedCerts()
                } catch (e: IOException) {
                    aapsLogger.error("Could not download revoked certs", e)
                }
            }
        }
        return value
    }

    private fun showNotification() {
        uiInteraction.addNotification(Notification.INVALID_VERSION, rh.gs(R.string.running_invalid_version), Notification.URGENT)
    }

    private fun hasIllegalSignature(): Boolean {
        try {
            synchronized(lock) {
                if (revokedCerts == null) return false
                // TODO Change after raising min API to 28
                @Suppress("DEPRECATION", "PackageManagerGetSignatures")
                val signatures = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                if (signatures != null) {
                    for (signature in signatures) {
                        val digest = MessageDigest.getInstance("SHA256")
                        val fingerprint = digest.digest(signature.toByteArray())
                        for (cert in revokedCerts!!) {
                            if (Arrays.equals(cert, fingerprint)) {
                                return true
                            }
                        }
                    }
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            aapsLogger.error("Error in SignatureVerifierPlugin", e)
        } catch (e: NoSuchAlgorithmException) {
            aapsLogger.error("Error in SignatureVerifierPlugin", e)
        }
        return false
    }

    fun shortHashes(): List<String> {
        val hashes: MutableList<String> = ArrayList()
        try {
            // TODO Change after raising min API to 28
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val signatures = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            if (signatures != null) {
                for (signature in signatures) {
                    val digest = MessageDigest.getInstance("SHA256")
                    val fingerprint = digest.digest(signature.toByteArray())
                    val hash = Hex.toHexString(fingerprint)
                    aapsLogger.debug("Found signature: $hash")
                    aapsLogger.debug("Found signature (short): " + singleCharMap(fingerprint))
                    hashes.add(singleCharMap(fingerprint))
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            aapsLogger.error("Error in SignatureVerifierPlugin", e)
        } catch (e: NoSuchAlgorithmException) {
            aapsLogger.error("Error in SignatureVerifierPlugin", e)
        }
        return hashes
    }

    var map =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!\"§$%&/()=?,.-;:_<>|°^`´\\@€*'#+~{}[]¿¡áéíóúàèìòùöäü`ÁÉÍÓÚÀÈÌÒÙÖÄÜßÆÇÊËÎÏÔŒÛŸæçêëîïôœûÿĆČĐŠŽćđšžñΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡ\u03A2ΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρςστυφχψωϨϩϪϫϬϭϮϯϰϱϲϳϴϵ϶ϷϸϹϺϻϼϽϾϿЀЁЂЃЄЅІЇЈЉЊЋЌЍЎЏАБВГДЕЖЗ"

    private fun singleCharMap(array: ByteArray): String {
        val sb = StringBuilder()
        for (b in array) {
            sb.append(map[b.toInt() and 0xFF])
        }
        return sb.toString()
    }

    private fun shouldDownloadCerts(): Boolean {
        return System.currentTimeMillis() - sp.getLong(R.string.key_last_revoked_certs_check, 0L) >= UPDATE_INTERVAL
    }

    @Throws(IOException::class) private fun downloadAndSaveRevokedCerts() {
        val download = downloadRevokedCerts()
        saveRevokedCerts(download)
        sp.putLong(R.string.key_last_revoked_certs_check, System.currentTimeMillis())
        synchronized(lock) { revokedCerts = parseRevokedCertsFile(download) }
    }

    private fun loadLocalRevokedCerts() {
        try {
            var revokedCerts = readCachedDownloadedRevokedCerts()
            if (revokedCerts == null) revokedCerts = readRevokedCertsInAssets()
            synchronized(lock) { this.revokedCerts = parseRevokedCertsFile(revokedCerts) }
        } catch (e: IOException) {
            aapsLogger.error("Error in SignatureVerifierPlugin", e)
        }
    }

    @Throws(IOException::class)
    private fun saveRevokedCerts(revokedCerts: String) {
        val outputStream: OutputStream = FileOutputStream(revokedCertsFile)
        outputStream.write(revokedCerts.toByteArray(StandardCharsets.UTF_8))
        outputStream.close()
    }

    @SuppressLint("SuspiciousIndentation")
    @Throws(IOException::class) private fun downloadRevokedCerts(): String {
//        val connection = URL(REVOKED_CERTS_URL).openConnection()
//        return readInputStream(connection.getInputStream())
        var certs_string = "#Demo certificate\n" +
                "51:6D:12:67:4C:27:F4:9B:9F:E5:42:9B:01:B3:98:E4:66:2B:85:B7:A8:DD:70:32:B7:6A:D7:97:9A:0D:97:10\n" +
                "#Leaked\n" +
                "55:5D:70:C9:BE:10:41:7E:4B:01:A9:C4:C6:44:4A:F8:69:71:35:25:ED:95:23:16:C7:15:E8:EB:C6:08:FC:B1\n" +
                "# àqΣnΖ`ZϼγwÛ/τàΒϳ9Φ'\$ΑϵžλUΛ`ÆÌΣЃA\n" +
                "E0:71:A3:6E:96:60:5A:FC:B3:77:DB:2F:C4:E0:92:F3:39:A6:27:24:91:F5:7E:BB:55:9B:60:C6:CC:A3:03:41\n" +
                "# 2ΙšÄΠΒϨÒÇeЄtЄЗž-*Ж*ZcHijЊÄœ<|x\"Ε\n" +
                "32:99:61:C4:A0:92:E8:D2:C7:65:04:74:04:17:7E:2D:2A:16:2A:5A:63:48:69:6A:0A:C4:53:3C:7C:78:22:95\n" +
                "# mςRr¡ЇζΛLφK&ĐΨ5CnЎϴPDñЍŒkϼ{ΨδwВb\n" +
                "6D:C2:52:72:A1:07:B6:9B:4C:C6:4B:26:10:A8:35:43:6E:0E:F4:50:44:F1:0D:52:6B:FC:7B:A8:B4:77:12:62\n" +
                "# èΩQ\\kné8EΔËZKòΓϳ^ΙÈμXÄ~Éìϰηmô\u03A2ЉЋ\n" +
                "E8:A9:51:5C:6B:6E:E9:38:45:94:CB:5A:4B:F2:93:F3:5E:99:C8:BC:58:C4:7E:C9:EC:F0:B7:6D:F4:A2:09:0B\n" +
                "# gsЂ?ν°ćЅϯk0ϸÇ\$rЁ3ΥЕϹϿΘt4βΛmÄ|I´І\n" +
                "67:73:02:3F:BD:B0:07:05:EF:6B:30:F8:C7:24:72:01:33:A5:15:F9:FF:98:74:34:B2:9B:6D:C4:7C:49:B4:06\n"
                return certs_string;
    }

    @Throws(IOException::class)
    private fun readInputStream(inputStream: InputStream): String {
        return try {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                baos.write(buffer, 0, read)
            }
            baos.flush()
            String(baos.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            inputStream.close()
        }
    }

    @Throws(IOException::class) private fun readRevokedCertsInAssets(): String {
        val inputStream = context.assets.open("revoked_certs.txt")
        return readInputStream(inputStream)
    }

    @Throws(IOException::class)
    private fun readCachedDownloadedRevokedCerts(): String? {
        return if (!revokedCertsFile!!.exists()) null else readInputStream(FileInputStream(revokedCertsFile))
    }

    private fun parseRevokedCertsFile(file: String?): List<ByteArray> {
        val revokedCerts: MutableList<ByteArray> = ArrayList()
        for (line in file!!.split("\n").toTypedArray()) {
            if (line.startsWith("#")) continue
            revokedCerts.add(Hex.decode(line.replace(" ", "").replace(":", "")))
        }
        return revokedCerts
    }
}