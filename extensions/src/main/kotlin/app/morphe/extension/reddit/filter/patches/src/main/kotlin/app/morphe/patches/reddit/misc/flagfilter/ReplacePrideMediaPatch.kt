package app.morphe.patches.reddit.misc.flagfilter

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/filter/FlagFilterInterceptor;"

@Suppress("unused")
val replacePrideMediaPatch = bytecodePatch(
    name = "Replace pride flags and Snoo accessories",
    description = "Removes pride items from the Avatar Shop, replaces Snoo avatars wearing/holding them, and blocks pride flags in emojis, icons, and banners.",
) {
    compatibleWith("com.reddit.frontpage")
    extendWith("extensions/extension.mpe")

    execute {
        classes.forEach { classDef ->
            if (!classDef.type.startsWith("Lcom/reddit/") &&
                !classDef.type.startsWith("Lcoil/") &&
                !classDef.type.startsWith("Lcom/bumptech/glide/") &&
                !classDef.type.startsWith("Lokhttp3/")
            ) return@forEach

            classDef.methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                val instructions = implementation.instructions.toList()

                // Track hook insertion points: (index, register, hookType)
                // hookType: 0 = Bitmap, 1 = TextView, 2 = OkHttp ResponseBody.string() result
                val hooks = mutableListOf<Triple<Int, Int, Int>>()

                instructions.forEachIndexed { index, instr ->
                    if (instr.opcode == Opcode.INVOKE_VIRTUAL || instr.opcode == Opcode.INVOKE_DIRECT) {
                        val ref = (instr as? ReferenceInstruction)?.reference as? MethodReference
                            ?: return@forEachIndexed

                        // 1. Intercept BitmapDrawable & ImageView.setImageBitmap
                        if ((ref.definingClass == "Landroid/graphics/drawable/BitmapDrawable;" &&
                                    ref.name == "<init>" &&
                                    ref.parameterTypes == listOf("Landroid/content/res/Resources;", "Landroid/graphics/Bitmap;")) ||
                            (ref.definingClass == "Landroid/widget/ImageView;" &&
                                    ref.name == "setImageBitmap")
                        ) {
                            val regInstr = instr as FiveRegisterInstruction
                            val bitmapReg = if (ref.name == "<init>") regInstr.registerE else regInstr.registerD
                            hooks.add(Triple(index, bitmapReg, 0))
                        }

                        // 2. Intercept TextView.setText(CharSequence)
                        if (ref.definingClass == "Landroid/widget/TextView;" &&
                            ref.name == "setText" &&
                            ref.parameterTypes.firstOrNull() == "Ljava/lang/CharSequence;"
                        ) {
                            val regInstr = instr as FiveRegisterInstruction
                            hooks.add(Triple(index, regInstr.registerD, 1))
                        }

                        // 3. Intercept OkHttp ResponseBody.string() to scrub Avatar Shop & Snoo JSON
                        if (ref.definingClass == "Lokhttp3/ResponseBody;" &&
                            ref.name == "string" &&
                            index + 1 < instructions.size &&
                            instructions[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT
                        ) {
                            val moveInstr = instructions[index + 1] as OneRegisterInstruction
                            // Insert right AFTER move-result-object (at index + 2)
                            hooks.add(Triple(index + 2, moveInstr.registerA, 2))
                        }
                    }
                }

                if (hooks.isNotEmpty()) {
                    val mutableMethod: MutableMethod = proxy(classDef).mutableClass.methods.first {
                        it.name == method.name && it.parameterTypes == method.parameterTypes
                    }

                    for ((idx, reg, hookType) in hooks.sortedByDescending { it.first }) {
                        when (hookType) {
                            0 -> mutableMethod.addInstructions(
                                idx,
                                """
                                    invoke-static { v$reg }, $EXTENSION_CLASS->filterBitmap(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;
                                    move-result-object v$reg
                                """.trimIndent()
                            )
                            1 -> mutableMethod.addInstructions(
                                idx,
                                """
                                    invoke-static { v$reg }, $EXTENSION_CLASS->sanitizeText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                                    move-result-object v$reg
                                """.trimIndent()
                            )
                            2 -> mutableMethod.addInstructions(
                                idx,
                                """
                                    invoke-static { v$reg }, $EXTENSION_CLASS->filterNetworkPayload(Ljava/lang/String;)Ljava/lang/String;
                                    move-result-object v$reg
                                """.trimIndent()
                            )
                        }
                    }
                }
            }
        }
    }
}
