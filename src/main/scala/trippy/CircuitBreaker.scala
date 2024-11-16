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
   * Creates a circuit breaker where the protect call is locked to a single fibre at a time
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
   * Creates a circuit breaker that doesn't lock when used by multiple fibres in parallel
   * (and so doesn't really work correctly)
   */
  def make[F[_] : Async](maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): F[CircuitBreaker[F]] = {
    Ref.of[F, CircuitState](CircuitState.Closed(0)).map(of(_, maxFailures, resetTimeout, callTimeout))
  }

  private[trippy] def of[F[_] : Async](ref: Ref[F, CircuitState], maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): CircuitBreaker[F] =
    new CircuitBreaker[F] {
      override def state: F[CircuitState] = ref.get

      override def protect[A](fa: F[A], isFailure: PartialFunction[Either[Throwable, A], Boolean]): F[A] =
        (Async[F].realTimeInstant, ref.get).tupled.flatMap {
          case (now, CircuitState.Closed(failures)) => attemptAndUpdate(fa, ref, failures, isFailure, maxFailures, callTimeout, now)
          case (now, CircuitState.Open(resetAt)) =>
            if (now.isBefore(resetAt)) Async[F].raiseError(CircuitBreakerRejection)
            else attemptAndUpdate(fa, ref, 0, isFailure, maxFailures, callTimeout, now)
        }

      private def attemptAndUpdate[A](fa: F[A],
                                      ref: Ref[F, CircuitState],
                                      failures: Int,
                                      isFailure: PartialFunction[Either[Throwable, A], Boolean],
                                      maxFailures: Int,
                                      callTimeout: FiniteDuration,
                                      now: Instant): F[A] =
        Async[F].timeout(fa, callTimeout).attempt.flatMap { out =>
          val newState =
            if (!isFailure.applyOrElse(out, _ => false)) CircuitState.Closed(0)
            else if (failures + 1 < maxFailures) CircuitState.Closed(failures + 1)
            else CircuitState.Open(now.plusMillis(resetTimeout.toMillis))

          ref.set(newState) >> Async[F].rethrow(out.pure)
        }
    }
}
