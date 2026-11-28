/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5532
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/SmoothSeekbarPatch;"

val smoothSeekbarPatch = patch(
    description = "Adds an option to move the seekbar progress continuously while the video plays."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_smooth_seekbar", summary = true)
        )

        SeekbarCopyPlayedRectFingerprint.let {
            it.method.apply {
                val seekbarRegister = getInstruction<TwoRegisterInstruction>(
                    it.instructionMatches.first().index
                ).registerB
                val copyIndex = it.instructionMatches.last().index
                val playedRectRegister = getInstruction<OneRegisterInstruction>(copyIndex).registerA

                addInstruction(
                    copyIndex + 1,
                    "invoke-static { v$seekbarRegister, v$playedRectRegister }, $EXTENSION_CLASS->" +
                            "setPlayedRect(Landroid/view/View;Landroid/graphics/Rect;)V"
                )
            }
        }

        // Other patches change the drawing method.
        SeekbarOnDrawThumbXFingerprint.clearMatch()
        SeekbarOnDrawThumbXFingerprint.let {
            it.method.apply {
                // The later index first, so the earlier one is not shifted.
                val thumbXIndex = it.instructionMatches[1].index
                val thumbXInstruction = getInstruction<TwoRegisterInstruction>(thumbXIndex)
                val thumbXRegister = thumbXInstruction.registerA

                addInstructions(
                    thumbXIndex + 1,
                    """
                        invoke-static { v${thumbXInstruction.registerB}, v$thumbXRegister }, $EXTENSION_CLASS->getThumbX(Landroid/view/View;I)I
                        move-result v$thumbXRegister
                    """
                )

                // After the checks that skip the drawing, so a seekbar that draws nothing schedules no redraw.
                addInstructionsAtControlFlowLabel(
                    it.instructionMatches.first().index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onSeekbarDraw(Landroid/view/View;)V"
                )
            }
        }
    }
}
