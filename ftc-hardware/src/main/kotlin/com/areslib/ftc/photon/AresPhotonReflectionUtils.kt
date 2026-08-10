package com.areslib.ftc.photon

import java.lang.reflect.Field

/**
 * Reflection helpers isolated for the experimental Photon Lynx-module replacement path.
 * These operations bypass normal visibility and are initialization-only; never call them from a
 * robot hot loop. SDK layout changes are represented as a missing field rather than a hard failure.
 */
object AresPhotonReflectionUtils {
    /** Finds a field named [fieldName] on [clazz] or its superclass chain. */
    fun getField(clazz: Class<*>, fieldName: String): Field? {
        try {
            val f = clazz.getDeclaredField(fieldName)
            f.isAccessible = true
            return f
        } catch (e: NoSuchFieldException) {
            val superClass = clazz.superclass
            if (superClass != null) {
                return getField(superClass, fieldName)
            }
        }
        return null
    }

    /** Finds the first field whose declared type is exactly [target], including superclasses. */
    fun getField(clazz: Class<*>, target: Class<*>): Field? {
        for (f in clazz.declaredFields) {
            if (f.type == target) {
                f.isAccessible = true
                return f
            }
        }
        val superClass = clazz.superclass
        if (superClass != null) {
            return getField(superClass, target)
        }
        return null
    }

    /**
     * Shallow-copies fields declared directly by [org]'s runtime class into same-named fields on
     * [target]. Referenced objects are shared; source superclass fields are not traversed.
     */
    fun deepCopy(org: Any, target: Any) {
        val fields = org.javaClass.declaredFields
        for (f in fields) {
            f.isAccessible = true
            val f2 = getField(target.javaClass, f.name)
            if (f2 != null) {
                f2.isAccessible = true
                try {
                    f2.set(target, f.get(org))
                } catch (e: IllegalAccessException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
