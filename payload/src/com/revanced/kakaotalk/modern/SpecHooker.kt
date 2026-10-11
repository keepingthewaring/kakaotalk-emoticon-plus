package com.revanced.kakaotalk.modern

import com.revanced.kakaotalk.modern.dex.DexIndex
import com.revanced.kakaotalk.modern.dex.DexMethod
import com.revanced.kakaotalk.modern.dex.DexRef
import com.revanced.kakaotalk.modern.dex.MethodSpec
import java.lang.reflect.Executable
import java.lang.reflect.Method

internal class SpecHooker(
    private val runtime: ModernHookRuntime,
    val dex: DexIndex
) {
    private val classCache = HashMap<String, Class<*>?>()

    fun loadClass(descriptor: String): Class<*>? = classCache.getOrPut(descriptor) {
        runCatching {
            Class.forName(
                descriptor.removePrefix("L").removeSuffix(";").replace('/', '.'),
                false, runtime.classLoader
            )
        }.getOrNull()
    }

    fun resolve(spec: MethodSpec): List<DexMethod> =
        runCatching { spec.resolve(dex) }.getOrDefault(emptyList())

    fun resolveOne(spec: MethodSpec): DexMethod? = resolve(spec).firstOrNull()

    fun executable(method: DexMethod): Executable? {
        val clazz = loadClass(method.owner.descriptor) ?: return null
        return findExecutable(clazz, method)
    }

    private fun findExecutable(clazz: Class<*>, method: DexMethod): Executable? {
        if (method.name == "<init>" || method.name == "<clinit>") {
            return clazz.declaredConstructors.firstOrNull { ctor ->
                sameParams(ctor.parameterTypes, method.paramTypes)
            }
        }
        return clazz.declaredMethods.firstOrNull {
            it.name == method.name && sameSignature(it, method)
        } ?: generateSequence(clazz) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == method.name && sameSignature(it, method) }
    }

    private fun sameSignature(reflect: Method, dex: DexMethod): Boolean =
        sameParams(reflect.parameterTypes, dex.paramTypes) &&
            descriptorOf(reflect.returnType) == dex.returnType

    private fun sameParams(reflect: Array<Class<*>>, dex: List<String>): Boolean {
        if (reflect.size != dex.size) return false
        return reflect.indices.all { descriptorOf(reflect[it]) == dex[it] }
    }

    fun hook(spec: MethodSpec, callback: ModernHookCallback): Int {
        var count = 0
        resolve(spec).forEach { dexMethod ->
            val executable = executable(dexMethod) ?: return@forEach
            if (runtime.hook(executable, callback)) count++
        }
        return count
    }

    fun hookExecutable(executable: Executable, callback: ModernHookCallback): Boolean =
        runtime.hook(executable, callback)

    private fun methodByRef(ref: DexRef.Method): DexMethod? =
        dex.classes[ref.owner]?.methods?.firstOrNull {
            it.name == ref.name &&
                it.paramTypes == ref.params &&
                it.returnType == ref.ret
        } ?: dex.classes[ref.owner]?.methods?.firstOrNull {
            it.name == ref.name && it.paramTypes == ref.params
        }

    fun reflectMethod(ref: DexRef.Method): Method? {
        val clazz = loadClass(ref.owner) ?: return null
        val dex = methodByRef(ref)
        return generateSequence(clazz) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.name == ref.name &&
                    (dex == null || sameSignature(method, dex) ||
                        sameParams(method.parameterTypes, ref.params))
            }
    }

    companion object {
        fun descriptorOf(type: Class<*>): String = when {
            type.isPrimitive -> when (type) {
                java.lang.Boolean.TYPE -> "Z"; java.lang.Byte.TYPE -> "B"
                Character.TYPE -> "C"; java.lang.Short.TYPE -> "S"
                Integer.TYPE -> "I"; java.lang.Long.TYPE -> "J"
                java.lang.Float.TYPE -> "F"; java.lang.Double.TYPE -> "D"
                Void.TYPE -> "V"; else -> "L${type.name};"
            }
            type.isArray -> "[" + (type.componentType?.let { descriptorOf(it) } ?: "")
            else -> "L${type.name.replace('.', '/')};"
        }
    }
}
