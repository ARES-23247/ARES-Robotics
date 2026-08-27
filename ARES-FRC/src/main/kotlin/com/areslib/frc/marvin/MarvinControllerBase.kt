package com.areslib.frc.marvin

import com.areslib.Store
import com.areslib.subsystem.SubsystemControllerBase

/** Shared Redux dispatch-on-change support for Marvin mechanism facades. */
abstract class MarvinControllerBase(store: Store) : SubsystemControllerBase(store)
