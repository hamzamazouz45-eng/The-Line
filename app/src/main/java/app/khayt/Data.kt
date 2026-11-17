package app.khayt

import android.content.Context
import com.maxmind.db.Reader
import com.maxmind.geoip2.DatabaseReader
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.InetAddress

object Config {
    const val DEFAULT_LAT = 36.75
    const val DEFAULT_LON = 3.06
}

object Suffix {
    fun matches(set: Set<String>, domain: String): Boolean {
        var s = domain
        while (true) {
            if (set.contains(s)) return true
            val i = s.indexOf('.')
            if (i < 0) return false
            s = s.substring(i + 1)
        }
    }
}

object Trackers {
    @Volatile private var names: Set<String> = emptySet()

    fun load(ctx: Context) {
        if (names.isNotEmpty()) return
        names = ctx.assets.open("trackers.txt").bufferedReader().readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toHashSet()
    }

    fun isTracker(domain: String) = Suffix.matches(names, domain)
}

object Blocklist {
    @Volatile private var names: Set<String> = emptySet()

    fun load(ctx: Context) {
        names = ctx.getSharedPreferences("khayt", Context.MODE_PRIVATE)
            .getStringSet("blocked", emptySet())!!.toSet()
    }

    fun matches(domain: String) = Suffix.matches(names, domain)

    fun all(): Set<String> = names

    fun setBlocked(ctx: Context, domain: String, on: Boolean) {
        val n = names.toMutableSet()
        if (on) n.add(domain.lowercase()) else n.remove(domain.lowercase())
        names = n
        ctx.getSharedPreferences("khayt", Context.MODE_PRIVATE)
            .edit().putStringSet("blocked", n).apply()
    }
}

object Geo {
    data class Loc(val lat: Double, val lon: Double, val city: String, val cc: String)

    private var db: DatabaseReader? = null
    val available: Boolean get() = db != null

    fun init(ctx: Context) {
        if (db != null) return
        val f = File(ctx.filesDir, "GeoLite2-City.mmdb")
        try {
            if (!f.exists()) {
                ctx.assets.open("GeoLite2-City.mmdb").use { i ->
                    f.outputStream().use { o -> i.copyTo(o) }
                }
            }
            db = DatabaseReader.Builder(f).fileMode(Reader.FileMode.MEMORY_MAPPED).build()
        } catch (e: Exception) {
            f.delete()
            db = null
        }
    }

    fun locate(ip: String): Loc? {
        return try {
            val r = db?.tryCity(InetAddress.getByName(ip))?.orElse(null) ?: return null
            val la = r.location?.latitude ?: return null
            val lo = r.location?.longitude ?: return null
            Loc(la, lo, r.city?.name ?: "", r.country?.isoCode ?: "")
        } catch (e: Exception) {
            null
        }
    }
}

data class Event(
    val ts: Long,
    val domain: String,
    val ip: String,
    val lat: Double?,
    val lon: Double?,
    val city: String,
    val cc: String,
    val pkg: String,
    val app: String,
    val tracker: Boolean,
    val blocked: Boolean
) {
    fun json(): JSONObject = JSONObject()
        .put("ts", ts).put("domain", domain).put("ip", ip)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("city", city).put("cc", cc).put("pkg", pkg).put("app", app)
        .put("tracker", tracker).put("blocked", blocked)

    companion object {
        fun from(o: JSONObject) = Event(
            o.getLong("ts"), o.getString("domain"), o.getString("ip"),
            if (o.isNull("lat")) null else o.getDouble("lat"),
            if (o.isNull("lon")) null else o.getDouble("lon"),
            o.getString("city"), o.getString("cc"), o.getString("pkg"), o.getString("app"),
            o.getBoolean("tracker"), o.getBoolean("blocked")
        )
    }
}

object EventStore {
    private const val KEEP_MS = 8L * 3600_000L
    private val list = ArrayList<Event>()
    private var file: File? = null
    private var writer: FileWriter? = null
    private var adds = 0

    @Synchronized
    fun load(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "events.jsonl")
        file = f
        val cutoff = System.currentTimeMillis() - KEEP_MS
        if (f.exists()) {
            f.forEachLine { line ->
                try {
                    val e = Event.from(JSONObject(line))
                    if (e.ts >= cutoff) list.add(e)
                } catch (e: Exception) {
                }
            }
        }
        rewrite()
    }

    private fun rewrite() {
        writer?.close()
        val f = file ?: return
        f.writeText(list.joinToString("") { it.json().toString() + "\n" })
        writer = FileWriter(f, true)
    }

    @Synchronized
    fun add(e: Event) {
        list.add(e)
        writer?.apply {
            append(e.json().toString())
            append('\n')
            flush()
        }
        if (++adds % 500 == 0) {
            val cutoff = System.currentTimeMillis() - KEEP_MS
            list.removeAll { it.ts < cutoff }
            rewrite()
        }
    }

    @Synchronized
    fun since(ts: Long, limit: Int): List<Event> = list.filter { it.ts > ts }.takeLast(limit)

    @Synchronized
    fun history(): List<Event> = list.toList()
}
