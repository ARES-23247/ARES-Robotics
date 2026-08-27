package com.ares.analytics.service

/**
 * NT4 topic names shared by Analytics UI surfaces.
 *
 * Keep these values aligned with the robot publications instead of duplicating string literals in
 * individual cards. Topic names intentionally omit a leading slash, matching the ARES NT4
 * normalization contract.
 */
object RobotTopicContract {
    const val AVAILABLE_AUTONOMOUS_ROUTINES = "ARES/Auto/AvailableDocuments"
    const val SELECTED_AUTONOMOUS_ROUTINE = "ARES/Auto/Selected"
    const val AUTONOMOUS_STATUS = "ARES/Auto/Status"
    const val FTC_AUTONOMOUS_REQUEST = "ARES/Input/selectedAuto"
    const val FRC_AUTONOMOUS_REQUEST = "ARES/Auto/Requested"
    /** Compatibility publication for standard FRC dashboards and existing robot projects. */
    const val FRC_SMART_DASHBOARD_AUTONOMOUS_REQUEST = "SmartDashboard/SelectedAuto"
}
