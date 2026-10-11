package com.revanced.kakaotalk.modern.dex

import android.os.SharedMemory
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal class DexIndex private constructor(
    private val dexes: List<DexFileData>
) {
    var classes: Map<String, DexClass> = emptyMap()
        private set
    var classesBySource: Map<String, List<DexClass>> = emptyMap()
        private set

    init {
        val all = HashMap<String, DexClass>(dexes.sumOf { it.classes.size })
        val bySource = HashMap<String, MutableList<DexClass>>()
        dexes.forEach { dex ->
            dex.classes.values.forEach { clazz ->
                all.putIfAbsent(clazz.descriptor, clazz)
                clazz.sourceFile?.let { source ->
                    bySource.getOrPut(source) { ArrayList() }.add(clazz)
                }
            }
        }
        classes = all
        classesBySource = bySource
    }

    fun release() {
        classes = emptyMap()
        classesBySource = emptyMap()
        dexes.forEach { it.release() }
    }

    companion object {

        fun load(apkPaths: Collection<String>): DexIndex {
            val interner = ConcurrentHashMap<String, String>()
            val zips = ArrayList<ZipFile>()
            val entries = ArrayList<Pair<ZipFile, Pair<String, String>>>()
            apkPaths.forEach { path ->
                runCatching {
                    val zip = ZipFile(path)
                    zips += zip
                    zip.entries().asSequence()
                        .filter { it.name.matches(Regex("classes(\\d*)\\.dex")) }
                        .sortedBy { it.name }
                        .forEach { entries += zip to (it.name to path) }
                }
            }

            val dexes = arrayOfNulls<DexFileData>(entries.size)
            val workers = minOf(entries.size, Runtime.getRuntime().availableProcessors(), 6)
            val pool = Executors.newFixedThreadPool(workers.coerceAtLeast(1)) { runnable ->
                Thread(runnable, "emoplus-dexio").apply { isDaemon = true }
            }
            try {
                entries.forEachIndexed { index, (zip, meta) ->
                    val (name, _) = meta
                    pool.execute {
                        runCatching {
                            val entry = zip.getEntry(name) ?: return@runCatching
                            val shm = SharedMemory.create(name, entry.size.toInt())
                            val buf = shm.mapReadWrite().order(ByteOrder.LITTLE_ENDIAN)
                            zip.getInputStream(entry).use { input ->
                                val chunk = ByteArray(64 * 1024)
                                while (buf.hasRemaining()) {
                                    val n = input.read(chunk, 0, minOf(chunk.size, buf.remaining()))
                                    if (n < 0) break
                                    buf.put(chunk, 0, n)
                                }
                            }
                            buf.rewind()
                            dexes[index] = DexFileData.parse(buf, shm, interner)
                        }
                    }
                }
            } finally {
                pool.shutdown()
                runCatching { pool.awaitTermination(2, TimeUnit.MINUTES) }
                zips.forEach { runCatching { it.close() } }
            }
            return DexIndex(dexes.filterNotNull())
        }
    }
}

internal class DexFileData private constructor(
    var strings: List<String>,
    var types: List<String>,
    var protos: List<Proto>,
    var methods: List<MethodId>,
    var classes: Map<String, DexClass>,
    @Volatile private var bytes: ByteBuffer?,
    @Volatile private var sharedMemory: SharedMemory?
) {
    init {
        classes.values.forEach { clazz -> clazz.methods.forEach { it.dexFile = this } }
    }

    fun release() {
        bytes = null
        classes = emptyMap()
        strings = emptyList()
        types = emptyList()
        protos = emptyList()
        methods = emptyList()
        runCatching { sharedMemory?.close() }
        sharedMemory = null
    }

    class Proto(val returnType: String, val parameters: List<String>)
    class MethodId(val owner: String, val proto: Proto, val name: String)

    fun decodeInsns(method: DexMethod): List<DexInsn> {
        val buf = bytes ?: return emptyList()
        if (method.codeOff == 0 || method.codeOff + 16 > buf.limit()) return emptyList()
        var off = method.codeOff

        val insnsSize = u32(buf, off + 12).toInt()
        off += 16
        if (insnsSize <= 0 || off + insnsSize * 2L > buf.limit()) return emptyList()
        val insns = ArrayList<DexInsn>(insnsSize)
        var i = 0
        var safety = 0
        while (i < insnsSize && off >= 0 && off + 2 <= buf.limit() && safety++ < insnsSize + 8) {
            val unit = u16(buf, off)

            when (unit) {
                0x0100 -> {
                    val size = u16(buf, off + 2); val units = 4 + size * 2
                    off += units * 2; i += units; continue
                }
                0x0200 -> {
                    val size = u16(buf, off + 2); val units = 4 + size * 4
                    off += units * 2; i += units; continue
                }
                0x0300 -> {
                    val width = u16(buf, off + 2); val size = u32(buf, off + 4)
                    val units = (4 + (size * width + 1) / 2).toInt()
                    off += units * 2; i += units; continue
                }
            }
            val op = unit and 0xFF
            val insn = decodeInsn(op, buf, off)
            insns += insn
            val units = insn.units
            if (units <= 0) break
            off += units * 2
            i += units
        }
        return insns
    }

    private fun decodeInsn(op: Int, buf: ByteBuffer, off: Int): DexInsn {
        var ref: DexRef.Method? = null
        val units = when (op) {
            0x00 -> 1
            in 0x01..0x01, in 0x04..0x04, in 0x07..0x07, in 0x0a..0x0e,
            in 0x1d..0x1e, 0x21, 0x27, 0x28,
            in 0x7b..0x8f, in 0xb0..0xcf -> 1

            0x02, 0x05, 0x08, 0x29 -> 2
            0x03, 0x06, 0x09, 0x26, 0x2a, 0x2b, 0x2c -> 3

            in 0x0f..0x11 -> 1
            0x12 -> 1
            0x13, 0x15, 0x16, 0x19 -> 2
            0x14, 0x17 -> 3
            0x18 -> 5

            0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23 -> 2
            0x1b, 0x24, 0x25 -> 3

            in 0x2d..0x31, in 0x44..0x51 -> 2
            in 0x32..0x37, in 0x38..0x3d, in 0xd0..0xd7, in 0xd8..0xe2 -> 2
            in 0x3e..0x43, 0x73, in 0x79..0x7a, in 0xe3..0xf9 -> 1

            in 0x52..0x5f -> 2
            in 0x60..0x6d -> 2
            in 0x6e..0x72 -> { ref = methodRef(u16(buf, off + 2)); 3 }
            in 0x74..0x78 -> { ref = methodRef(u16(buf, off + 2)); 3 }
            0xfa, 0xfb -> { ref = methodRef(u16(buf, off + 2)); 4 }
            0xfc, 0xfd -> 3
            0xfe, 0xff -> 2
            else -> 1
        }
        return DexInsn(op, ref, units)
    }

    private fun methodRef(idx: Int): DexRef.Method? {
        val m = methods.getOrNull(idx) ?: return null
        return DexRef.Method(m.owner, m.name, m.proto.parameters, m.proto.returnType)
    }

    companion object {
        fun u16(b: ByteBuffer, o: Int): Int =
            (b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8)
        fun u32(b: ByteBuffer, o: Int): Long =
            (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

        private fun uleb(b: ByteBuffer, o: Int): Pair<Long, Int> {
            var result = 0L; var shift = 0; var off = o
            while (true) {
                val byte = b.get(off++).toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            return result to off
        }

        private fun mutf8(b: ByteBuffer, o: Int): String {
            val (len, dataOff) = uleb(b, o)
            var end = dataOff
            while (end < b.limit() && b.get(end).toInt() != 0) end++

            val raw = ByteArray(end - dataOff)
            b.duplicate().apply { position(dataOff) }.get(raw)
            return runCatching {
                val input = DataInputStream(ByteArrayInputStream(raw))
                val sb = StringBuilder(len.toInt())
                var c: Int
                while (true) {
                    c = try { input.readUnsignedByte() } catch (e: EOFException) { break }
                    when {
                        c == 0 -> break
                        c < 0x80 -> sb.append(c.toChar())
                        c and 0xE0 == 0xC0 -> {
                            val c2 = input.readUnsignedByte()
                            sb.append(((c and 0x1F shl 6) or (c2 and 0x3F)).toChar())
                        }
                        c and 0xF0 == 0xE0 -> {
                            val c2 = input.readUnsignedByte(); val c3 = input.readUnsignedByte()
                            sb.append(((c and 0x0F shl 12) or (c2 and 0x3F shl 6) or (c3 and 0x3F)).toChar())
                        }
                        else -> break
                    }
                }
                sb.toString()
            }.getOrElse {
                String(raw, Charsets.UTF_8)
            }
        }

        fun parse(bytes: ByteBuffer, shm: SharedMemory, interner: ConcurrentHashMap<String, String>): DexFileData {
            fun u32at(o: Int) = u32(bytes, o).toInt()
            val stringIdsSize = u32at(0x38); val stringIdsOff = u32at(0x3C)
            val typeIdsSize = u32at(0x40); val typeIdsOff = u32at(0x44)
            val protoIdsSize = u32at(0x48); val protoIdsOff = u32at(0x4C)
            val methodIdsSize = u32at(0x58); val methodIdsOff = u32at(0x5C)
            val classDefsSize = u32at(0x60); val classDefsOff = u32at(0x64)

            fun intern(s: String): String = interner.putIfAbsent(s, s) ?: s
            val strings = ArrayList<String>(stringIdsSize)
            for (i in 0 until stringIdsSize) {
                val dataOff = u32at(stringIdsOff + i * 4)
                strings += intern(mutf8(bytes, dataOff))
            }
            val types = ArrayList<String>(typeIdsSize)
            for (i in 0 until typeIdsSize) {
                types += strings.getOrElse(u32at(typeIdsOff + i * 4)) { "" }
            }
            val protos = ArrayList<Proto>(protoIdsSize)
            for (i in 0 until protoIdsSize) {
                val base = protoIdsOff + i * 12
                val returnType = types.getOrElse(u32at(base + 4)) { "V" }
                val paramsOff = u32at(base + 8)
                val params = ArrayList<String>()
                if (paramsOff != 0) {
                    val size = u32at(paramsOff)
                    for (p in 0 until size) {
                        params += types.getOrElse(u16(bytes, paramsOff + 4 + p * 2)) { "L" }
                    }
                }
                protos += Proto(returnType, params)
            }
            val methods = ArrayList<MethodId>(methodIdsSize)
            for (i in 0 until methodIdsSize) {
                val base = methodIdsOff + i * 8
                methods += MethodId(
                    owner = types.getOrElse(u16(bytes, base)) { "" },
                    proto = protos.getOrElse(u16(bytes, base + 2)) { Proto("V", emptyList()) },
                    name = strings.getOrElse(u32at(base + 4)) { "" }
                )
            }

            val classes = HashMap<String, DexClass>(classDefsSize)
            for (i in 0 until classDefsSize) {
                val base = classDefsOff + i * 32
                val descriptor = types.getOrNull(u32at(base)) ?: continue
                val sourceIdx = u32at(base + 16)
                val sourceFile = if (sourceIdx == 0xFFFFFFFF.toInt()) null
                    else strings.getOrNull(sourceIdx)
                val classDataOff = u32at(base + 24)
                val classMethods = ArrayList<DexMethod>()
                val clazz = DexClass(descriptor, sourceFile, classMethods)
                if (classDataOff != 0) {
                    var off = classDataOff
                    val (sfs, o1) = uleb(bytes, off); off = o1
                    val (ifs, o2) = uleb(bytes, off); off = o2
                    val (dms, o3) = uleb(bytes, off); off = o3
                    val (vms, o4) = uleb(bytes, off); off = o4

                    for (count in listOf(sfs.toInt(), ifs.toInt())) {
                        for (k in 0 until count) {
                            off = uleb(bytes, off).second
                            val (_, o6) = uleb(bytes, off); off = o6
                        }
                    }
                    for (count in listOf(dms.toInt(), vms.toInt())) {
                        var methodIdx = 0
                        for (k in 0 until count) {
                            val (diff, o5) = uleb(bytes, off); off = o5
                            val (accessFlags, o6) = uleb(bytes, off); off = o6
                            val (codeOff, o7) = uleb(bytes, off); off = o7
                            methodIdx += diff.toInt()
                            val m = methods.getOrNull(methodIdx) ?: continue
                            classMethods += DexMethod(
                                clazz, m.name, accessFlags.toInt(),
                                m.proto.returnType, m.proto.parameters, codeOff.toInt()
                            )
                        }
                    }
                }
                classes[descriptor] = clazz
            }
            return DexFileData(strings, types, protos, methods, classes, bytes, shm)
        }
    }
}

internal class DexClass(
    val descriptor: String,
    val sourceFile: String?,
    val methods: List<DexMethod>
) {
    val isInner: Boolean get() = '$' in descriptor
}

internal class DexMethod(
    val owner: DexClass,
    val name: String,
    val accessFlags: Int,
    val returnType: String,
    val paramTypes: List<String>,
    val codeOff: Int
) {
    @Volatile private var decoded: List<DexInsn>? = null
    var dexFile: DexFileData? = null

    val insns: List<DexInsn>
        get() = decoded ?: (dexFile?.decodeInsns(this) ?: emptyList()).also { decoded = it }
}

internal class DexInsn(
    val opcode: Int,
    val ref: DexRef.Method?,
    val units: Int
)

internal sealed class DexRef {
    class Method(val owner: String, val name: String, val params: List<String>, val ret: String) : DexRef()
}
