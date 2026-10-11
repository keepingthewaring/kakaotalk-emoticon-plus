package com.revanced.kakaotalk.modern.dex

internal object Op {
    const val MOVE_RESULT = 0x0A
    const val CONST_4 = 0x12
    const val IF_EQZ = 0x38
    const val SGET_OBJECT = 0x62
    const val INVOKE_VIRTUAL = 0x6E
}

internal fun typeMatches(pattern: String, actual: String): Boolean = when {
    pattern == "L" -> actual.startsWith("L") && actual.endsWith(";")
    pattern.endsWith("/") -> actual.startsWith(pattern)
    else -> pattern == actual
}

internal fun paramsMatch(pattern: List<String>, actual: List<String>): Boolean {
    if (pattern.size != actual.size) return false
    return pattern.indices.all { typeMatches(pattern[it], actual[it]) }
}

internal sealed class InsnSpec {
    abstract fun matches(insn: DexInsn, index: Int, method: DexMethod): Boolean

    class Op(val opcode: Int) : InsnSpec() {
        override fun matches(insn: DexInsn, index: Int, method: DexMethod) =
            insn.opcode == opcode
    }

    class Call(
        val opcodes: Set<Int>? = null,
        val owner: String? = null,
        val name: String? = null,
        val params: List<String>? = null,
        val ret: String? = null,
    ) : InsnSpec() {
        override fun matches(insn: DexInsn, index: Int, method: DexMethod): Boolean {
            val ref = insn.ref ?: return false
            if (opcodes != null && insn.opcode !in opcodes) return false
            if (insn.opcode !in INVOKE_OPS) return false
            if (owner != null) {
                val want = if (owner == "this") method.owner.descriptor else owner
                if (!typeMatches(want, ref.owner)) return false
            }
            if (name != null && ref.name != name) return false
            if (params != null && !paramsMatch(params, ref.params)) return false
            if (ret != null && !typeMatches(ret, ref.ret)) return false
            return true
        }
    }

    companion object {
        val INVOKE_OPS = setOf(
            com.revanced.kakaotalk.modern.dex.Op.INVOKE_VIRTUAL,
            0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78, 0xFA, 0xFB
        )
    }
}

internal class MethodSpec(
    val sourceFile: String? = null,
    val definingClass: String? = null,
    val includeInner: Boolean = true,
    val name: String? = null,
    val accessFlags: Int = 0,
    val returnType: String? = null,
    val parameters: List<String>? = null,
    val filters: List<InsnSpec> = emptyList(),
) {
    fun resolve(index: DexIndex): List<DexMethod> {
        val out = ArrayList<DexMethod>()
        for (clazz in candidateClasses(index)) {
            for (method in clazz.methods) {
                if (matches(method)) out.add(method)
            }
        }
        return out
    }

    private fun candidateClasses(index: DexIndex): Collection<DexClass> {
        definingClass?.let { desc ->
            index.classes[desc]?.let { return listOf(it) }
            return emptyList()
        }
        sourceFile?.let { return index.classesBySource[it].orEmpty() }
        return index.classes.values
    }

    private fun matches(method: DexMethod): Boolean {
        val clazz = method.owner
        if (sourceFile != null && clazz.sourceFile != sourceFile) return false
        if (!includeInner && clazz.isInner) return false
        if (name != null && method.name != name) return false
        if (accessFlags != 0 && method.accessFlags and accessFlags != accessFlags) return false
        if (returnType != null && !typeMatches(returnType, method.returnType)) return false
        if (parameters != null && !paramsMatch(parameters, method.paramTypes)) return false
        if (filters.isNotEmpty() && !matchSubsequence(method, method.insns)) return false
        return true
    }

    private fun matchSubsequence(method: DexMethod, insns: List<DexInsn>): Boolean {
        var position = 0
        for (spec in filters) {
            var found = -1
            var i = position
            while (i < insns.size) {
                if (spec.matches(insns[i], i, method)) { found = i; break }
                i++
            }
            if (found < 0) return false
            position = found + 1
        }
        return true
    }
}
