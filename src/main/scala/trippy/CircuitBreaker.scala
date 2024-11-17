package trippy

import cats.effect.std.Mutex
import cats.effect.{Async, Ref}
import cats.syntax.all.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait CircuitBreaker[F[_]] {
  def state: F[CircuitState]

  def protect[A](fa: F[A], isFailure: PartialFunction[Either[Throwable, A], Boolean]): F[A]

  def protect[A](fa: F[A]): F[A] = protect(fa, PartialFunction.fromFunction(_.isLeft))
}

object CircuitBreaker {

  /**
   * Creates a circuit breaker where the protect call is locked to a single fibre at a time.
   * This favours correctness of state updates and circuit breaker transitions over throughput.
   */
  def locking[F[_] : Async](maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): F[CircuitBreaker[F]] = {
    for {
      ref <- Ref.of[F, CircuitState](CircuitState.Closed(0))
      mtx <- Mutex.apply[F]
      underlying = of(ref, maxFailures, resetTimeout, callTimeout)
    } yield new CircuitBreaker[F] {
      override def state: F[CircuitState] = underlying.state

      override def protect[A](fa: F[A], isFailure: PartialFunction[Either[Throwable, A], Boolean]): F[A] =
        mtx.lock.surround(underlying.protect(fa, isFailure))
    }
  }

  /**
   * Creates a circuit breaker that doesn't lock when used by multiple fibres in parallel.
   * This favours throughput over correctness and may lead to missed updates and
   * transitions not happening at the exact `maxFailures` specified.
   */
  def nonLocking[F[_] : Async](maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): F[CircuitBreaker[F]] =
    Ref.of[F, CircuitState](CircuitState.Closed(0)).map(of(_, maxFailures, resetTimeout, callTimeout))

  private[trippy] def of[F[_] : Async](ref: Ref[F, CircuitState], maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): CircuitBreaker[F] =
    new CircuitBreaker[F] {
      override def state: F[CircuitState] = ref.get

      override def protect[A](fa: F[A], isFailure: PartialFunction[Either[Throwable, A], Boolean]): F[A] = {
        def attempt(failures: Int, now: Instant): F[A] =
          Async[F].timeout(fa, callTimeout).attempt.flatMap { out =>
            val newState =
              if (!isFailure.applyOrElse(out, _ => false)) CircuitState.Closed(0)
              else if (failures + 1 < maxFailures) CircuitState.Closed(failures + 1)
              else CircuitState.Open(now.plusMillis(resetTimeout.toMillis))

            ref.flatModify(_ => (newState, Async[F].rethrow(out.pure)))
          }

        (Async[F].realTimeInstant, ref.get).tupled.flatMap {
          case (now, CircuitState.Open(resetAt)) if now.isBefore(resetAt) => Async[F].raiseError(CircuitBreakerRejection)
          case (now, CircuitState.Open(resetAt)) => attempt(0, now)
          case (now, CircuitState.Closed(failures)) => attempt(failures, now)
        }
      }
    }
}
