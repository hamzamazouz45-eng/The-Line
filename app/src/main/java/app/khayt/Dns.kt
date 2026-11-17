package app.khayt

object Dns {
    class Question(val name: String, val type: Int, val end: Int)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    fun question(p: ByteArray): Question? {
        if (p.size < 17) return null
        var i = 12
        val sb = StringBuilder()
        while (i < p.size) {
            val len = p[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if ((len and 0xC0) != 0) return null
            i++
            if (i + len > p.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(p, i, len, Charsets.US_ASCII).lowercase())
            i += len
        }
        if (i + 4 > p.size) return null
        return Question(sb.toString(), u16(p, i), i + 4)
    }

    fun nxdomain(q: ByteArray, qEnd: Int): ByteArray {
        val r = q.copyOfRange(0, qEnd)
        r[2] = (0x80 or (q[2].toInt() and 0x01)).toByte()
        r[3] = 0x83.toByte()
        for (k in 6..11) r[k] = 0
        return r
    }

    fun ipv4Answers(r: ByteArray, qEnd: Int): List<String> {
        val out = ArrayList<String>()
        if (r.size < 12) return out
        val an = u16(r, 6)
        var i = qEnd
        for (n in 0 until an) {
            if (i >= r.size) return out
            while (i < r.size) {
                val b = r[i].toInt() and 0xFF
                if (b == 0) {
                    i++
                    break
                }
                if ((b and 0xC0) == 0xC0) {
                    i += 2
                    break
                }
                i += 1 + b
            }
            if (i + 10 > r.size) return out
            val type = u16(r, i)
            val len = u16(r, i + 8)
            i += 10
            if (i + len > r.size) return out
            if (type == 1 && len == 4) {
                out.add(
                    "${r[i].toInt() and 0xFF}.${r[i + 1].toInt() and 0xFF}." +
                        "${r[i + 2].toInt() and 0xFF}.${r[i + 3].toInt() and 0xFF}"
                )
            }
            i += len
        }
        return out
    }
}

object Ip {
    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    fun udp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        b[2] = (total shr 8).toByte()
        b[3] = total.toByte()
        b[8] = 64
        b[9] = 17
        src.copyInto(b, 12)
        dst.copyInto(b, 16)
        val ck = checksum(b, 0, 20)
        b[10] = (ck shr 8).toByte()
        b[11] = ck.toByte()
        b[20] = (sport shr 8).toByte()
        b[21] = sport.toByte()
        b[22] = (dport shr 8).toByte()
        b[23] = dport.toByte()
        val ul = 8 + payload.size
        b[24] = (ul shr 8).toByte()
        b[25] = ul.toByte()
        payload.copyInto(b, 28)
        return b
    }
}
