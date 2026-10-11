package com.revanced.kakaotalk.modern

import com.revanced.kakaotalk.modern.dex.DexRef
import com.revanced.kakaotalk.modern.dex.InsnSpec
import com.revanced.kakaotalk.modern.dex.MethodSpec
import com.revanced.kakaotalk.modern.dex.Op

internal object EmoticonPlusFeature {

    fun install(hooker: SpecHooker): Int {
        var n = 0

        n += hooker.hook(MethodSpec(
            definingClass = "Lcom/kakao/talk/emoticon/membership/data/remote/dto/EmoticonPlusMeResult;",
            name = "<init>",
            accessFlags = PUBLIC or CONSTRUCTOR,
            parameters = listOf("Z", "J", "J"), returnType = "V"
        )) { _, args ->
            args[0] = true
            ModernHookOutcome.Proceed(args)
        }

        n += hooker.hook(MethodSpec(
            sourceFile = "UserPreferencesExt.kt",
            accessFlags = PUBLIC or STATIC or FINAL,
            returnType = "Z",
            filters = listOf(
                InsnSpec.Call(
                    owner = "Lcom/kakao/talk/module/emoticon/contract/EmoticonModuleFacade;",
                    name = "getPlusManager"
                )
            )
        )) { _, args ->
            ModernHookOutcome.Return(true, args)
        }

        val managerCheck = hooker.resolveOne(MethodSpec(
            sourceFile = "EmoticonPlusManager.kt",
            accessFlags = PUBLIC or FINAL,
            parameters = emptyList(), returnType = "Z",
            filters = listOf(
                InsnSpec.Op(Op.SGET_OBJECT),
                InsnSpec.Op(Op.CONST_4),
                InsnSpec.Op(Op.IF_EQZ),
                InsnSpec.Op(Op.INVOKE_VIRTUAL),
                InsnSpec.Op(Op.MOVE_RESULT)
            )
        ))

        val managerExec = managerCheck?.let { hooker.executable(it) }
        if (managerExec != null &&
            hooker.hookExecutable(managerExec) { _, args ->
                ModernHookOutcome.Return(true, args)
            }) n++
        val meResultMethod = managerCheck?.insns
            ?.firstOrNull { it.opcode == Op.INVOKE_VIRTUAL }
            ?.ref
        val meExec = meResultMethod?.let { hooker.reflectMethod(it) }
        if (meExec != null &&
            hooker.hookExecutable(meExec) { _, args ->
                ModernHookOutcome.Return(true, args)
            }) n++

        for (src in listOf("EmoticonPlusManagerHelperImpl.kt", "DummyEmoticonPlusManagerHelper.kt")) {
            n += hooker.hook(MethodSpec(
                sourceFile = src,
                accessFlags = PUBLIC,
                parameters = emptyList(), returnType = "Z"
            )) { _, args ->
                ModernHookOutcome.Return(true, args)
            }
        }
        return n
    }

    private const val PUBLIC = 0x0001
    private const val STATIC = 0x0008
    private const val CONSTRUCTOR = 0x10000
    private const val FINAL = 0x0010
}
