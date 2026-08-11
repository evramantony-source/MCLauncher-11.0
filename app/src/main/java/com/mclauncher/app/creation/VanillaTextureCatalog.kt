package com.mclauncher.app.creation

internal const val VANILLA_TEXTURE_ROOT = "assets/minecraft/textures/"

/**
 * Resource-pack-safe PNGs from Minecraft's complete vanilla texture tree.
 *
 * Modern item rendering is not confined to textures/item: block items, armor,
 * chests, signs, shields and other special models reuse block, entity,
 * equipment and additional vanilla texture folders.
 */
internal fun isEditableVanillaTexturePath(path: String): Boolean =
    path.startsWith(VANILLA_TEXTURE_ROOT) &&
        path.endsWith(".png", ignoreCase = true) &&
        !path.contains('\\') &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }

internal fun vanillaTextureRelativePath(path: String): String =
    path.removePrefix(VANILLA_TEXTURE_ROOT).removeSuffix(".png")

internal fun vanillaTextureDisplayName(path: String): String =
    vanillaTextureRelativePath(path).replace("/", " › ").replace('_', ' ')
