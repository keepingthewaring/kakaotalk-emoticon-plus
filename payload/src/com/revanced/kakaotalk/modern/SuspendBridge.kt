package com.revanced.kakaotalk.modern

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object SuspendBridge {

    fun call(receiver: Any, method: Method, args: Array<Any?>, timeoutMs: Long = 20_000): Any? {
        val appCl = receiver.javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val contType = Class.forName("kotlin.coroutines.Continuation", false, appCl)
        val emptyContext = emptyCoroutineContext(appCl) ?: emptyContextProxy(appCl)
        val resultClass = Class.forName("kotlin.Result", false, appCl)

        val latch = CountDownLatch(1)
        val box = AtomicReference<Any?>()
        val self = AtomicReference<Any?>()
        val proxy = Proxy.newProxyInstance(appCl, arrayOf(contType), InvocationHandler { _, m, a ->
            when (m.name) {
                "getContext" -> emptyContext
                "resumeWith" -> {
                    box.set(a?.firstOrNull())
                    latch.countDown()
                    null
                }
                "toString" -> "EmoPlusContinuation"
                "hashCode" -> System.identityHashCode(box)
                "equals" -> a?.firstOrNull() === self.get()
                else -> null
            }
        })
        self.set(proxy)

        val ret = try {
            method.invoke(receiver, *args, proxy)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }

        val isSuspended = ret != null && ret.javaClass.isEnum &&
            (ret as Enum<*>).name == "COROUTINE_SUSPENDED"
        val value = if (isSuspended) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            box.get()
        } else ret
        return unwrap(value, resultClass)
    }

    private fun emptyCoroutineContext(appCl: ClassLoader): Any? = runCatching {
        val cls = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, appCl)

        cls.declaredFields
            .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == cls }
            ?.apply { isAccessible = true }?.get(null)
            ?: cls.getField("INSTANCE").get(null)
    }.getOrNull()

    private fun emptyContextProxy(appCl: ClassLoader): Any {
        val ctxType = Class.forName("kotlin.coroutines.CoroutineContext", false, appCl)
        val self = AtomicReference<Any?>()
        val proxy = Proxy.newProxyInstance(appCl, arrayOf(ctxType), InvocationHandler { _, m, a ->
            when (m.name) {
                "get" -> null
                "fold" -> a?.firstOrNull()
                "plus" -> a?.firstOrNull() ?: self.get()
                "minusKey" -> self.get()
                "toString" -> "EmoPlusEmptyContext"
                "hashCode" -> System.identityHashCode(self.get())
                "equals" -> a?.firstOrNull() === self.get()
                else -> null
            }
        })
        self.set(proxy)
        return proxy
    }

    private fun unwrap(value: Any?, resultClass: Class<*>): Any? {
        if (value == null || !resultClass.isInstance(value)) return value
        val inner = resultClass.declaredFields
            .firstOrNull { !Modifier.isStatic(it.modifiers) }
            ?.apply { isAccessible = true }?.get(value)
        if (inner != null && inner.javaClass.name.startsWith(resultClass.name + "\$")) {
            val ex = inner.javaClass.declaredFields
                .firstOrNull { Throwable::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }?.get(inner) as? Throwable
            throw RuntimeException("suspend call failed: $ex", ex)
        }
        return inner
    }
}
