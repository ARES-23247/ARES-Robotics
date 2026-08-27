package edu.wpi.first.networktables

class PubSubOption

class NetworkTableInstance {
    companion object {
        @JvmStatic
        fun getDefault(): NetworkTableInstance = NetworkTableInstance()
    }

    fun getDoubleTopic(name: String): DoubleTopic = DoubleTopic(name)
    fun getBooleanTopic(name: String): BooleanTopic = BooleanTopic(name)
    fun getStringTopic(name: String): StringTopic = StringTopic(name)
    fun getDoubleArrayTopic(name: String): DoubleArrayTopic = DoubleArrayTopic(name)

    fun getEntry(name: String): NetworkTableEntry = NetworkTableEntry(name)
}

class NetworkTableEntry(val name: String) {
    fun setDouble(value: Double) {
        DoublePublisher.lastValues[name] = value
        lastValues[name] = value
    }
    fun setBoolean(value: Boolean) {
        BooleanPublisher.lastValues[name] = value
        lastValues[name] = value
    }
    fun setString(value: String) {
        StringPublisher.lastValues[name] = value
        lastValues[name] = value
    }
    fun setDoubleArray(value: DoubleArray) {
        DoubleArrayPublisher.lastValues[name] = value
        lastValues[name] = value
    }
    fun getDouble(defaultValue: Double): Double = (lastValues[name] as? Double) ?: defaultValue
    fun getBoolean(defaultValue: Boolean): Boolean = (lastValues[name] as? Boolean) ?: defaultValue
    fun getString(defaultValue: String): String = (lastValues[name] as? String) ?: defaultValue

    companion object {
        val lastValues = mutableMapOf<String, Any>()
    }
}

class DoubleTopic(val name: String) {
    fun publish(vararg options: PubSubOption): DoublePublisher = DoublePublisher(name)
}
class DoublePublisher(val name: String) {
    companion object {
        val lastValues = mutableMapOf<String, Double>()
    }
    fun set(v: Double) {
        lastValues[name] = v
        NetworkTableEntry.lastValues[name] = v
    }
}

class BooleanTopic(val name: String) {
    fun publish(vararg options: PubSubOption): BooleanPublisher = BooleanPublisher(name)
}
class BooleanPublisher(val name: String) {
    companion object {
        val lastValues = mutableMapOf<String, Boolean>()
    }
    fun set(v: Boolean) {
        lastValues[name] = v
        NetworkTableEntry.lastValues[name] = v
    }
}

class StringTopic(val name: String) {
    fun publish(vararg options: PubSubOption): StringPublisher = StringPublisher(name)
}
class StringPublisher(val name: String) {
    companion object {
        val lastValues = mutableMapOf<String, String>()
    }
    fun set(v: String) {
        lastValues[name] = v
        NetworkTableEntry.lastValues[name] = v
    }
}

class DoubleArrayTopic(val name: String) {
    fun publish(vararg options: PubSubOption): DoubleArrayPublisher = DoubleArrayPublisher(name)
}
class DoubleArrayPublisher(val name: String) {
    companion object {
        val lastValues = mutableMapOf<String, DoubleArray>()
    }
    fun set(v: DoubleArray) {
        lastValues[name] = v
        NetworkTableEntry.lastValues[name] = v
    }
}
