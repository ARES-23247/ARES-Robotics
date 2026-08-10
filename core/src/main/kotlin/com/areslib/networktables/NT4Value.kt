package com.areslib.networktables

/**
 * Sealed interface representing typed NT4 values in ARESLib-Kotlin.
 * Provides type-safe representations for all supported NT4 data types.
 */
sealed interface NT4Value {
    val typeString: String
    fun getAsObject(): Any

    data class BooleanVal(val value: Boolean) : NT4Value {
        override val typeString: String = "boolean"
        override fun getAsObject(): Any = value
    }

    data class DoubleVal(val value: Double) : NT4Value {
        override val typeString: String = "double"
        override fun getAsObject(): Any = value
    }

    data class LongVal(val value: Long) : NT4Value {
        override val typeString: String = "int"
        override fun getAsObject(): Any = value
    }

    data class FloatVal(val value: Float) : NT4Value {
        override val typeString: String = "float"
        override fun getAsObject(): Any = value
    }

    data class StringVal(val value: String) : NT4Value {
        override val typeString: String = "string"
        override fun getAsObject(): Any = value
    }

    class BooleanArrayVal(value: BooleanArray) : NT4Value {
        private val snapshot = value.copyOf()
        val value: BooleanArray get() = snapshot.copyOf()
        override val typeString: String = "boolean[]"
        override fun getAsObject(): Any = snapshot.copyOf()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BooleanArrayVal) return false
            return snapshot.contentEquals(other.snapshot)
        }
        override fun hashCode(): Int = snapshot.contentHashCode()
    }

    class DoubleArrayVal(value: DoubleArray) : NT4Value {
        private val snapshot = value.copyOf()
        val value: DoubleArray get() = snapshot.copyOf()
        override val typeString: String = "double[]"
        override fun getAsObject(): Any = snapshot.copyOf()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DoubleArrayVal) return false
            return snapshot.contentEquals(other.snapshot)
        }
        override fun hashCode(): Int = snapshot.contentHashCode()
    }

    class LongArrayVal(value: LongArray) : NT4Value {
        private val snapshot = value.copyOf()
        val value: LongArray get() = snapshot.copyOf()
        override val typeString: String = "int[]"
        override fun getAsObject(): Any = snapshot.copyOf()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LongArrayVal) return false
            return snapshot.contentEquals(other.snapshot)
        }
        override fun hashCode(): Int = snapshot.contentHashCode()
    }

    class FloatArrayVal(value: FloatArray) : NT4Value {
        private val snapshot = value.copyOf()
        val value: FloatArray get() = snapshot.copyOf()
        override val typeString: String = "float[]"
        override fun getAsObject(): Any = snapshot.copyOf()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FloatArrayVal) return false
            return snapshot.contentEquals(other.snapshot)
        }
        override fun hashCode(): Int = snapshot.contentHashCode()
    }

    class StringArrayVal(value: Array<String>) : NT4Value {
        private val snapshot = value.copyOf()
        val value: Array<String> get() = snapshot.copyOf()
        override val typeString: String = "string[]"
        override fun getAsObject(): Any = snapshot.copyOf()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StringArrayVal) return false
            return snapshot.contentEquals(other.snapshot)
        }
        override fun hashCode(): Int = snapshot.contentHashCode()
    }

    companion object {
        fun fromId(id: Int): NT4Type = when (id) {
            0 -> NT4Type.BOOLEAN
            1 -> NT4Type.DOUBLE
            2 -> NT4Type.INT
            3 -> NT4Type.FLOAT
            4 -> NT4Type.STRING
            16 -> NT4Type.BOOLEAN_ARRAY
            17 -> NT4Type.DOUBLE_ARRAY
            18 -> NT4Type.INT_ARRAY
            19 -> NT4Type.FLOAT_ARRAY
            20 -> NT4Type.STRING_ARRAY
            else -> NT4Type.UNKNOWN
        }

        fun fromObject(obj: Any?): NT4Value = when (obj) {
            is Boolean -> BooleanVal(obj)
            is Double -> DoubleVal(obj)
            is Float -> FloatVal(obj)
            is Number -> LongVal(obj.toLong())
            is String -> StringVal(obj)
            is BooleanArray -> BooleanArrayVal(obj)
            is DoubleArray -> DoubleArrayVal(obj)
            is FloatArray -> FloatArrayVal(obj)
            is IntArray -> LongArrayVal(obj.map { it.toLong() }.toLongArray())
            is LongArray -> LongArrayVal(obj)
            is Array<*> -> {
                if (obj.isArrayOf<String>()) {
                    @Suppress("UNCHECKED_CAST")
                    StringArrayVal(obj as Array<String>)
                } else {
                    StringVal(obj.joinToString(","))
                }
            }
            null -> StringVal("")
            else -> StringVal(obj.toString())
        }
    }
}

enum class NT4Type(val id: Int, val typeString: String) {
    BOOLEAN(0, "boolean"),
    DOUBLE(1, "double"),
    INT(2, "int"),
    FLOAT(3, "float"),
    STRING(4, "string"),
    BOOLEAN_ARRAY(16, "boolean[]"),
    DOUBLE_ARRAY(17, "double[]"),
    INT_ARRAY(18, "int[]"),
    FLOAT_ARRAY(19, "float[]"),
    STRING_ARRAY(20, "string[]"),
    UNKNOWN(-1, "unknown")
}
