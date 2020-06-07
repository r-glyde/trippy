package com.glyde.trippy

sealed abstract class CircuitBreakerError(msg: String) extends RuntimeException(msg)

object CircuitBreakerError {
  case object CircuitBreakerRejection extends CircuitBreakerError("Task execution rejected by circuit breaker")
  case object CircuitBreakerTimeout   extends CircuitBreakerError("Task execution exceeded given timeout")
}
