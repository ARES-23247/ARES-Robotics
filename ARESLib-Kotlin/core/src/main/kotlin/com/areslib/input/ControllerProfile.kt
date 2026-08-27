package com.areslib.input

/** Stable information an adapter can use to choose a [ControllerProfile]. */
data class ControllerIdentity(
    val name: String,
    val guid: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val axisCount: Int = 0,
    val buttonCount: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "controller name must not be blank" }
        require(axisCount >= 0) { "axisCount must not be negative" }
        require(buttonCount >= 0) { "buttonCount must not be negative" }
    }
}

/** Front or rear artwork on which a GUI should place a control marker. */
enum class ControllerView { FRONT, REAR }

/**
 * Normalized marker position for an interactive controller diagram.
 *
 * Coordinates use the inclusive `[0, 1]` range, independent of artwork resolution.
 */
data class ControlVisualAnchor(
    val view: ControllerView,
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && x in 0.0..1.0) { "anchor x must be in [0, 1]" }
        require(y.isFinite() && y in 0.0..1.0) { "anchor y must be in [0, 1]" }
    }
}

/** A physical input presented to students by its stable project ID and friendly label. */
sealed interface ControlDescriptor {
    val id: String
    val label: String
    val anchor: ControlVisualAnchor?
}

/** A digital control backed by one zero-based raw HID button. */
data class ButtonControlDescriptor(
    override val id: String,
    override val label: String,
    val buttonIndex: Int,
    override val anchor: ControlVisualAnchor? = null,
) : ControlDescriptor {
    init {
        validateControlText(id, label)
        require(buttonIndex >= 0) { "buttonIndex must not be negative" }
    }
}

/** An analog control backed by one zero-based raw HID axis. */
data class AxisControlDescriptor(
    override val id: String,
    override val label: String,
    val axisIndex: Int,
    val defaultTransform: AxisTransform = AxisTransform(),
    override val anchor: ControlVisualAnchor? = null,
) : ControlDescriptor {
    init {
        validateControlText(id, label)
        require(axisIndex >= 0) { "axisIndex must not be negative" }
    }
}

/** Optional identity constraints for automatic profile selection. */
data class ControllerProfileMatch(
    val vendorId: Int? = null,
    val productId: Int? = null,
    val guid: String? = null,
    val nameContains: String? = null,
) {
    init {
        require(nameContains == null || nameContains.isNotBlank()) { "nameContains must not be blank" }
    }

    fun matches(identity: ControllerIdentity): Boolean {
        if (vendorId != null && vendorId != identity.vendorId) return false
        if (productId != null && productId != identity.productId) return false
        if (guid != null && !guid.equals(identity.guid, ignoreCase = true)) return false
        return nameContains == null || identity.name.contains(nameContains, ignoreCase = true)
    }
}

/**
 * Project-stable description of a controller's physical inputs and GUI markers.
 *
 * Profiles describe hardware; they do not contain robot actions. A saved controls document refers
 * to [ControlDescriptor.id], while the profile maps that ID to the raw input reported by a platform
 * adapter. This separation permits the same binding document to use friendly names such as `m1`
 * without assuming a Flydigi, Xbox, or FTC-specific raw button number.
 */
class ControllerProfile(
    val id: String,
    val displayName: String,
    controls: List<ControlDescriptor>,
    val match: ControllerProfileMatch = ControllerProfileMatch(),
) {
    private val controlArray: Array<ControlDescriptor> = controls.toTypedArray()

    /** Minimum frame axis capacity needed to represent every declared axis. */
    val requiredAxisCapacity: Int

    /** Minimum frame button capacity needed to represent every declared button. */
    val requiredButtonCapacity: Int

    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(displayName.isNotBlank()) { "profile displayName must not be blank" }
        require(controlArray.isNotEmpty()) { "a controller profile must declare at least one control" }

        var maximumAxis = -1
        var maximumButton = -1
        var outer = 0
        while (outer < controlArray.size) {
            when (val control = controlArray[outer]) {
                is AxisControlDescriptor -> if (control.axisIndex > maximumAxis) maximumAxis = control.axisIndex
                is ButtonControlDescriptor -> if (control.buttonIndex > maximumButton) maximumButton = control.buttonIndex
            }
            var inner = outer + 1
            while (inner < controlArray.size) {
                require(controlArray[outer].id != controlArray[inner].id) {
                    "duplicate control id '${controlArray[outer].id}'"
                }
                inner++
            }
            outer++
        }
        requiredAxisCapacity = maximumAxis + 1
        requiredButtonCapacity = maximumButton + 1
    }

    val controlCount: Int get() = controlArray.size

    fun controlAt(index: Int): ControlDescriptor = controlArray[index]

    /** Profile lookup is a setup/editor operation and is intentionally not a robot-loop API. */
    fun findControl(controlId: String): ControlDescriptor? {
        var index = 0
        while (index < controlArray.size) {
            if (controlArray[index].id == controlId) return controlArray[index]
            index++
        }
        return null
    }

    fun requireButton(controlId: String): ButtonControlDescriptor =
        findControl(controlId) as? ButtonControlDescriptor
            ?: throw IllegalArgumentException("'$controlId' is not a button in profile '$id'")

    fun requireAxis(controlId: String): AxisControlDescriptor =
        findControl(controlId) as? AxisControlDescriptor
            ?: throw IllegalArgumentException("'$controlId' is not an axis in profile '$id'")
}

private fun validateControlText(id: String, label: String) {
    require(id.isNotBlank()) { "control id must not be blank" }
    require(label.isNotBlank()) { "control label must not be blank" }
}
