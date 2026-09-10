package com.campusai.core.designsystem

data class GlassEffects(
    val deformation: Boolean = true,
    val rimLight: Boolean = true,
    val aurora: Boolean = true,
    val meteors: Boolean = true,
) {
    internal fun active(dark: Boolean, motion: Boolean) = GlassEffects(
        deformation = deformation && motion,
        rimLight = rimLight && motion,
        aurora = aurora && dark && motion,
        meteors = meteors && dark && motion,
    )
}
