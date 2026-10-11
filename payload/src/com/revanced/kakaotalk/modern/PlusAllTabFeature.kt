package com.revanced.kakaotalk.modern

import com.revanced.kakaotalk.modern.dex.MethodSpec
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal object PlusAllTabFeature {

    private const val VM_SOURCE = "EmoticonKeyboardViewModel.kt"
    private const val REPO_SOURCE = "ItemResourceRepositoryImpl.kt"
    private const val SUBTYPE = "com.kakao.talk.module.emoticon.data.StoreItemSubType"
    private const val CONT = "kotlin.coroutines.Continuation"

    private const val MAX_TABS = 25

    private var hooker: SpecHooker? = null

    @Volatile private var fetchStarted = false

    @Volatile private var plusItems: Map<String, Array<String?>> = emptyMap()

    @Volatile private var plusEmots: Map<String, List<Any>> = emptyMap()

    fun install(hooker: SpecHooker): Int {
        this.hooker = hooker
        var n = 0

        n += hooker.hook(MethodSpec(
            sourceFile = VM_SOURCE,
            includeInner = false,
            accessFlags = PUBLIC,
            parameters = listOf("L", "Ljava/util/List;"),
            returnType = "Ljava/util/List;"
        )) { _, args ->
            ModernHookOutcome.Proceed(injectItems(args))
        }

        n += hooker.hook(MethodSpec(
            sourceFile = REPO_SOURCE,
            includeInner = false,
            accessFlags = PUBLIC,
            parameters = listOf("Ljava/lang/String;"),
            returnType = "Ljava/util/List;"
        )) { _, args ->
            val code = args.getOrNull(0) as? String
            val res = code?.let { plusEmots[it] }
            if (res != null) {
                ModernHookOutcome.Return(res, args)
            } else ModernHookOutcome.Proceed(args)
        }

        kick()
        return n
    }

    private fun injectItems(args: Array<Any?>): Array<Any?> {
        val metas = plusItems
        if (metas.isEmpty()) return args
        @Suppress("UNCHECKED_CAST")
        val items = args[1] as? List<Any?> ?: return args
        val donor = items.firstOrNull { it != null && isItem(it) } ?: return args
        val shaper = shaperFor(donor) ?: return args

        val existing = items.mapNotNullTo(HashSet()) { shaper.codeOf(it) }
        val merged = ArrayList<Any?>(items.size + metas.size)
        merged.addAll(items)
        var added = 0
        for ((code, meta) in metas) {
            if (code in existing) continue

            val vals = shaper.valuesOf(donor) ?: continue
            if (shaper.codePos >= 0) vals[shaper.codePos] = code
            if (shaper.titlePos >= 0) vals[shaper.titlePos] = meta[0]
            if (shaper.namePos >= 0) vals[shaper.namePos] = meta[1]
            if (shaper.imagePos >= 0) meta[2]?.let { vals[shaper.imagePos] = it }
            if (shaper.iconOnPos >= 0) meta[3]?.let { vals[shaper.iconOnPos] = it }
            if (shaper.iconOffPos >= 0) meta[4]?.let { vals[shaper.iconOffPos] = it }
            runCatching { shaper.copier.invoke(donor, *vals) }
                .getOrNull()?.let { merged.add(it); added++ }
        }
        if (added == 0) return args
        args[1] = merged
        return args
    }

    private fun isItem(o: Any): Boolean =
        o.javaClass.declaredMethods.any {
            it.parameterCount >= 20 && it.returnType == o.javaClass
        }

    @Volatile private var shaper: ItemShaper? = null

    private class ItemShaper(
        val copier: java.lang.reflect.Method,
        val fieldForParam: Array<Field?>,
        val codePos: Int, val titlePos: Int, val namePos: Int, val imagePos: Int,
        val iconOnPos: Int, val iconOffPos: Int,
    ) {
        fun valuesOf(donor: Any): Array<Any?>? = runCatching {
            Array(fieldForParam.size) { i -> fieldForParam[i]?.get(donor) }
        }.getOrNull()

        fun codeOf(item: Any?): String? = runCatching {
            fieldForParam.getOrNull(codePos)?.get(item) as? String
        }.getOrNull()
    }

    private fun shaperFor(donor: Any): ItemShaper? {
        shaper?.let { return it }
        synchronized(this) {
            shaper?.let { return it }
            val cls = donor.javaClass
            val fields = cls.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }.onEach { it.isAccessible = true }
            val copier = cls.declaredMethods.firstOrNull {
                it.parameterCount == fields.size && it.returnType == cls
            } ?: return null
            copier.isAccessible = true
            val types = copier.parameterTypes

            val queues = HashMap<Class<*>, ArrayDeque<Field>>()
            for (f in fields) queues.getOrPut(f.type) { ArrayDeque() }.add(f)
            val fieldForParam = arrayOfNulls<Field>(types.size)
            for (i in types.indices) {
                fieldForParam[i] = queues[types[i]]?.removeFirstOrNull()
                    ?: return null
            }
            fun getter(name: String): Any? = runCatching {
                cls.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 &&
                        it.returnType == String::class.java
                }?.invoke(donor)
            }.getOrNull()
            fun posOf(v: Any?, taken: Set<Int>): Int {
                if (v == null) return -1
                for (i in fieldForParam.indices) {
                    if (i in taken) continue
                    if (fieldForParam[i]?.get(donor) == v) return i
                }
                return -1
            }
            val taken = HashSet<Int>()
            fun locate(name: String): Int {
                val p = posOf(getter(name), taken)
                if (p >= 0) taken += p
                return p
            }
            var codePos = locate("o")
            if (codePos < 0) {

                for (i in fieldForParam.indices) {
                    if (i in taken) continue
                    val v = fieldForParam[i]?.get(donor)
                    if (v is String && v.matches(Regex("\\d{5,}"))) { codePos = i; break }
                }
                if (codePos >= 0) taken += codePos
            }

            fun posBySuffix(suffix: String): Int {
                for (i in fieldForParam.indices) {
                    if (i in taken) continue
                    val v = fieldForParam[i]?.get(donor)
                    if (v is String && v.endsWith(suffix)) { taken += i; return i }
                }
                return -1
            }
            val s = ItemShaper(
                copier, fieldForParam, codePos,
                locate("t"), locate("d"), locate("u"),
                posBySuffix("icon_on.png"), posBySuffix("icon_off.png")
            )
            shaper = s
            return s
        }
    }

    private fun kick() {
        if (fetchStarted) return
        synchronized(this) {
            if (fetchStarted) return
            fetchStarted = true
        }
        Thread(
            { runCatching { fetch() } },
            "emoplus-plus-tabs"
        ).apply { isDaemon = true }.start()
    }

    private fun fetch() {
        val h = hooker ?: return

        var api: Any? = null
        for (attempt in 1..40) {
            api = component(h)?.let { resolveApi(it) }
            if (api != null) break
            Thread.sleep(3000)
        }
        if (api == null) return

        val codes = LinkedHashSet<String>()
        val rawSections = LinkedHashMap<String, List<*>>()
        for (q in SEARCH_QUERIES) {
            runCatching { harvestSearch(api, q, codes, rawSections) }
            if (rawSections.size >= MAX_TABS) break
        }
        if (codes.isEmpty()) return

        val metas = LinkedHashMap<String, Array<String?>>()
        for (code in codes.take(MAX_TABS)) {
            runCatching {
                val info = fetchItemInfo(api, code) ?: return@runCatching
                parseItemInfo(info)?.let { a ->
                    metas[a[0] as String] =
                        arrayOf(a[1] as? String, a[2] as? String, a[3] as? String,
                                a[4] as? String, a[5] as? String)
                }
            }
        }
        if (metas.isEmpty()) return

        val emots = HashMap<String, List<Any>>(rawSections.size)
        for ((code, rows) in rawSections) {
            val vgs = rows.filterNotNull()
            if (vgs.isNotEmpty()) emots[code] = vgs
        }
        plusEmots = emots
        plusItems = metas
    }

    private fun component(h: SpecHooker): Any? = runCatching {
        val compCls = h.loadClass("Lv4o;") ?: return@runCatching null
        val companion = compCls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type != compCls }
            .mapNotNull { f -> runCatching {
                f.isAccessible = true
                f.get(null)
            }.getOrNull() }
            .firstOrNull { v ->
                v.javaClass.methods.any { it.parameterCount == 0 && it.returnType == compCls }
            } ?: return@runCatching null
        companion.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && it.returnType == compCls }
            ?.invoke(companion)
    }.getOrNull()

    private fun resolveApi(component: Any): Any? = runCatching {
        val repo = component.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && it.returnType.name.contains("kd00") }
            ?.invoke(component) ?: return@runCatching null
        for (f in repo.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            if (f.type.methods.none { it.name == "get" && it.parameterCount == 0 }) continue
            f.isAccessible = true
            val provider = runCatching { f.get(repo) }.getOrNull() ?: continue
            val svc = runCatching { provider.javaClass.getMethod("get").invoke(provider) }
                .getOrNull() ?: continue
            if (looksLikeApi(svc)) return@runCatching svc
        }
        null
    }.getOrNull()

    private fun looksLikeApi(svc: Any): Boolean =
        svc.javaClass.methods.any {
            it.parameterCount == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[3].name == CONT
        }

    private fun harvestSearch(
        service: Any,
        query: String,
        out: LinkedHashSet<String>,
        sections: LinkedHashMap<String, List<*>>
    ) {
        for (m in service.javaClass.methods) {
            if (out.size >= MAX_TABS) break
            if (m.parameterCount != 2) continue
            if (m.parameterTypes[1].name != CONT) continue
            val formCtor = m.parameterTypes[0].declaredConstructors.firstOrNull {
                it.parameterCount == 3 && !it.isSynthetic &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Integer.TYPE &&
                    it.parameterTypes[2] == Integer::class.java
            } ?: continue
            formCtor.isAccessible = true
            val form = formCtor.newInstance(query, 24, null)
            val response = SuspendBridge.call(service, m, arrayOf(form)) ?: continue
            collectCodes(response, out, 0)
            collectSections(response, sections, 0)
        }
    }

    private fun collectSections(
        o: Any?,
        out: LinkedHashMap<String, List<*>>,
        depth: Int
    ) {
        if (o == null || depth > 8 || out.size >= MAX_TABS) return
        when (o) {
            is Iterable<*> -> { o.forEach { collectSections(it, out, depth + 1) }; return }
            is Map<*, *> -> { o.values.forEach { collectSections(it, out, depth + 1) }; return }
            is String, is Number, is Boolean, is Enum<*>, is Char -> return
        }
        val name = o.javaClass.name
        if (name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("android.")) return

        var code: String? = null
        for (f in o.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
            f.isAccessible = true
            val v = runCatching { f.get(o) }.getOrNull()
            if (v is String && v.matches(Regex("\\d{5,}"))) { code = v; break }
        }
        if (code != null && !out.containsKey(code)) {
            for (m in o.javaClass.methods) {
                if (m.parameterCount != 0) continue
                if (!List::class.java.isAssignableFrom(m.returnType)) continue
                val list = runCatching { m.invoke(o) as? List<*> }.getOrNull()
                if (!list.isNullOrEmpty() && list[0] != null &&
                    list[0]!!.javaClass.name.contains("vg00")) {
                    @Suppress("UNCHECKED_CAST")
                    out[code] = list as List<Any>
                    return
                }
            }
        }
        val fields = o.javaClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }.onEach { it.isAccessible = true }
        for (f in fields) {
            collectSections(runCatching { f.get(o) }.getOrNull(), out, depth + 1)
        }
    }

    private fun collectCodes(o: Any?, out: LinkedHashSet<String>, depth: Int) {
        if (o == null || depth > 6 || out.size >= MAX_TABS) return
        when (o) {
            is String -> { if (o.matches(Regex("\\d{5,}"))) out.add(o); return }
            is Iterable<*> -> { o.forEach { collectCodes(it, out, depth + 1) }; return }
            is Map<*, *> -> { o.values.forEach { collectCodes(it, out, depth + 1) }; return }
            is Number, is Boolean, is Enum<*>, is Char -> return
        }
        val name = o.javaClass.name
        if (name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("android.")) return
        for (f in o.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
            f.isAccessible = true
            collectCodes(runCatching { f.get(o) }.getOrNull(), out, depth + 1)
        }
    }

    private fun fetchItemInfo(service: Any, code: String): Any? {
        val m = service.javaClass.methods.firstOrNull {
            it.parameterCount == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                it.parameterTypes[2] == java.lang.Boolean.TYPE &&
                it.parameterTypes[3].name == CONT &&
                it.returnType == Any::class.java
        } ?: return null
        val resp = SuspendBridge.call(service, m, arrayOf(code, false, false)) ?: return null
        val list = resp.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
            ?.invoke(resp) as? List<*> ?: return null
        return list.firstOrNull()
    }

    private fun parseItemInfo(info: Any): Array<Any?>? {
        val fields = info.javaClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }.onEach { it.isAccessible = true }
        val b = fields.indexOfFirst { it.type.name == SUBTYPE }
        if (b < 1 || b + 8 >= fields.size) return null
        fun s(i: Int) = runCatching { fields[i].get(info) as? String }.getOrNull()
        val code = s(b - 1) ?: return null
        val title = s(b + 1) ?: s(b + 2) ?: code
        val name = s(b + 2) ?: title
        return arrayOf(code, title, name, s(b + 5), s(b + 6), s(b + 7))
    }

    private val SEARCH_QUERIES = listOf("ㅋㅋ", "ㅎㅎ", "사랑", "굿", "ok", "감사")

    private const val PUBLIC = 0x0001
}
