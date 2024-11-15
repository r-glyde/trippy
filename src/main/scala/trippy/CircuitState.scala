package trippy

import java.time.Instant

enum CircuitState {
  case Closed(failures: Int)
  case Open(resetAt: Instant)
}