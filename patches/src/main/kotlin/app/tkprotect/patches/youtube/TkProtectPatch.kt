package app.tkprotect.patches.youtube

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS =
    "Lapp/tkprotect/patches/youtube/extension/TkProtect;"

/**
 * Morphe's own add-on entry point. Every add-on bundle inserts a call to its
 * registration method at the start of this method, so this hook is stable across
 * YouTube versions (it belongs to the Morphe extension, not to YouTube).
 */
private object AddOnRegisterFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/youtube/addon/AddOnManager;",
    name = "registerAddOns",
    returnType = "V",
    parameters = listOf(),
)

@Suppress("unused")
val tkProtectPatch = bytecodePatch(
    name = "TKProtect panel",
    description = "Adds a TK button to the player overlay. It opens a panel bound to the video " +
        "currently playing, with local chat and comment hiding. Long press to change settings.",
    default = true,
) {
    extendWith("extensions/youtube.dex")
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        AddOnRegisterFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->register()V",
        )
    }
}
