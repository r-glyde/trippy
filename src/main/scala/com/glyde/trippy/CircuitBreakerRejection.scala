package com.glyde.trippy

import java.util.concurrent.RejectedExecutionException

case object CircuitBreakerRejection extends RuntimeException("Task execution rejected by circuit breaker")
