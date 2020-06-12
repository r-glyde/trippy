package com.glyde.trippy

import scala.concurrent.duration.FiniteDuration

sealed trait CircuitState extends Product with Serializable

final case class Closed(failures: Int)                            extends CircuitState
final case class Open(openedAt: Long, resetAfter: FiniteDuration) extends CircuitState
case object HalfOpen                                              extends CircuitState
