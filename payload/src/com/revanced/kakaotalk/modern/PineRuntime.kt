package com.revanced.kakaotalk.modern

import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Executable
import java.lang.reflect.Method

internal sealed interface ModernHookOutcome {
    data class Proceed(val args: Array<Any?>) : ModernHookOutcome
    data class Return(val value: Any?, val args: Array<Any?>) : ModernHookOutcome
}

internal typealias ModernHookCallback =
    (Pine.CallFrame, Array<Any?>) -> ModernHookOutcome

internal class ModernHookRuntime(val classLoader: ClassLoader) {

    fun hook(
        executable: Executable,
        callback: ModernHookCallback
    ): Boolean = runCatching {
        Pine.hook(executable, object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val args = callFrame.args ?: emptyArray()
                val outcome = try {
                    callback(callFrame, args)
                } catch (throwable: Throwable) {
                    ModernHookOutcome.Proceed(args)
                }
                when (outcome) {
                    is ModernHookOutcome.Proceed -> {

                        if (outcome.args !== args && callFrame.args != null) {
                            val dst = callFrame.args
                            val n = minOf(dst.size, outcome.args.size)
                            System.arraycopy(outcome.args, 0, dst, 0, n)
                        }
                    }
                    is ModernHookOutcome.Return -> {
                        if (executable !is Method ||
                            acceptsReturn(executable, outcome.value)) {
                            callFrame.setResult(outcome.value)
                        }
                    }
                }
            }
        })
        true
    }.getOrDefault(false)

    private fun acceptsReturn(executable: Executable, value: Any?): Boolean {
        if (executable !is Method) return true
        val returnType = executable.returnType
        if (returnType == Void.TYPE) return true
        if (!returnType.isPrimitive) return value == null || returnType.isInstance(value)
        return when (returnType) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Byte.TYPE -> value is Byte
            Character.TYPE -> value is Char
            java.lang.Short.TYPE -> value is Short
            Integer.TYPE -> value is Int
            java.lang.Long.TYPE -> value is Long
            java.lang.Float.TYPE -> value is Float
            java.lang.Double.TYPE -> value is Double
            else -> false
        }
    }
}
