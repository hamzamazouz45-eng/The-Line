package app.khayt

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * يلتقط استعلامات DNS فقط: يوجّه عنوان DNS وهميًا (10.7.0.2) إلى النفق،
 * أما بقية حركة الإنترنت فتمر طبيعيًا خارج النفق ولا تتأثر.
 * لكل استعلام: نحدد التطبيق المرسل، نمرره إلى 1.1.1.1 (أو نرد بـ NXDOMAIN إن كان محجوبًا)،
 * ثم نسجّل النطاق وموقع الخادم في EventStore.
 */
class KhaytVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "app.khayt.STOP"
        const val VDNS = "10.7.0.2"
        const val UPSTREAM = "1.1.1.1"
    }

    private var tun: ParcelFileDescriptor? = null
    private var overlay: OverlayWindow? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false
    private val lastLogged = ConcurrentHashMap<String, Long>()
    private val locCache = ConcurrentHashMap<String, Geo.Loc>()
    private val labels = ConcurrentHashMap<String, String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        Trackers.load(this)
        Blocklist.load(this)
        Geo.init(this)
        EventStore.load(this)

        val fd = Builder()
            .setSession("Khayt")
            .addAddress("10.7.0.1", 32)
            .addDnsServer(VDNS)
            .addRoute(VDNS, 32)
            .addDisallowedApplication(packageName)
            .setBlocking(true)
            .establish() ?: return START_NOT_STICKY

        tun = fd
        running = true
        pool = Executors.newCachedThreadPool()
        thread(name = "khayt-dns") { loop(fd) }
        overlay = OverlayWindow(this).also { it.show() }
        return START_STICKY
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                handle(buf, n, output)
            }
        } catch (e: Exception) {
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun handle(pkt: ByteArray, n: Int, out: FileOutputStream) {
        if (n < 28 || (pkt[0].toInt() shr 4) != 4) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt[9].toInt() != 17 || n < ihl + 8) return
        val sport = u16(pkt, ihl)
        if (u16(pkt, ihl + 2) != 53) return
        val src = pkt.copyOfRange(12, 16)
        val dst = pkt.copyOfRange(16, 20)
        val query = pkt.copyOfRange(ihl + 8, n)
        pool?.execute { answer(src, dst, sport, query, out) }
    }

    private fun answer(src: ByteArray, dst: ByteArray, sport: Int, query: ByteArray, out: FileOutputStream) {
        val q = Dns.question(query)
        val (pkg, label) = appOf(owner(src, sport, dst))
        val resp: ByteArray?
        if (q != null && Blocklist.matches(q.name)) {
            resp = Dns.nxdomain(query, q.end)
            if (q.type == 1) log(q.name, null, pkg, label, true)
        } else {
            resp = forward(query)
            if (resp != null && q != null && q.type == 1) {
                log(q.name, Dns.ipv4Answers(resp, q.end).firstOrNull(), pkg, label, false)
            }
        }
        if (resp == null) return
        val packet = Ip.udp(dst, src, 53, sport, resp)
        try {
            synchronized(out) { out.write(packet) }
        } catch (e: Exception) {
        }
    }

    private fun forward(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 4000
                s.send(DatagramPacket(query, query.size, InetAddress.getByName(UPSTREAM), 53))
                val r = ByteArray(4096)
                val p = DatagramPacket(r, r.size)
                s.receive(p)
                r.copyOf(p.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun owner(src: ByteArray, sport: Int, dst: ByteArray): Int {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(src), sport),
                InetSocketAddress(InetAddress.getByAddress(dst), 53)
            )
        } catch (e: Exception) {
            -1
        }
    }

    private fun appOf(uid: Int): Pair<String, String> {
        if (uid < 0) return "?" to "غير معروف"
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return "uid:$uid" to "النظام ($uid)"
        val label = labels.getOrPut(pkg) {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
        }
        return pkg to label
    }

    private fun log(domain: String, ip: String?, pkg: String, app: String, blocked: Boolean) {
        val now = System.currentTimeMillis()
        val key = "$pkg|$domain"
        val prev = lastLogged[key]
        if (prev != null && now - prev < 3000) return
        lastLogged[key] = now
        var loc = ip?.let { Geo.locate(it) }
        if (loc != null) locCache[domain] = loc else loc = locCache[domain]
        EventStore.add(
            Event(
                now, domain, ip ?: "", loc?.lat, loc?.lon, loc?.city ?: "", loc?.cc ?: "",
                pkg, app, Trackers.isTracker(domain), blocked
            )
        )
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        overlay?.hide()
        pool?.shutdownNow()
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        super.onDestroy()
    }
}
