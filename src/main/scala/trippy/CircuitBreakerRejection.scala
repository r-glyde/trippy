package trippy

case object CircuitBreakerRejection extends Exception("Task rejected by open circuit breaker")
