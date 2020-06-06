package com.glyde.trippy

import java.time.Instant

sealed trait CircuitState extends Product with Serializable

final case class Closed(failures: Int) extends CircuitState
final case class Open(openedAt: Long)  extends CircuitState
case object HalfOpen                   extends CircuitState
