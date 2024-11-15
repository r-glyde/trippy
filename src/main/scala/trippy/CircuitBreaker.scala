package trippy

import cats.effect.{Async, Ref}
import cats.syntax.all.*

import scala.concurrent.duration.FiniteDuration

trait CircuitBreaker[F[_]] {
  def protect[A](fa: F[A], isFailure: PartialFunction[Either[Throwable, A], Boolean]): F[A]

  def protect[A](fa: F[A]): F[A] = protect(fa, PartialFunction.fromFunction(_.isLeft))

  def state: F[CircuitState]
}

object CircuitBreaker {
  def make[F[_] : Async](maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): F[CircuitBreaker[F]] =
    Ref.of[F, CircuitState](CircuitState.Closed(0)).map(of(_, maxFailures, resetTimeout, callTimeout))

  private[trippy] def of[F[_] : Async](ref: Ref[F, CircuitState], maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): CircuitBreaker[F] =
    new CircuitBreaker[F] {
      override def protect[A](fa: F[A], isFailure: PartialFunction[Either[Throwable, A], Boolean]): F[A] =
        (Async[F].realTimeInstant, ref.get).tupled.flatMap {
          case (now, CircuitState.Closed(failures)) => Async[F].timeout(fa, callTimeout).attempt.flatMap { out =>
            val newState =
              if (!isFailure.applyOrElse(out, _ => false)) CircuitState.Closed(0)
              else if (failures + 1 < maxFailures) CircuitState.Closed(failures + 1)
              else CircuitState.Open(now.plusMillis(resetTimeout.toMillis))

            ref.set(newState) >> Async[F].rethrow(out.pure)
          }
          case (now, CircuitState.Open(resetAt)) =>
            if (now.isAfter(resetAt)) ref.set(CircuitState.Closed(0)) >> protect(fa)
            else Async[F].raiseError(CircuitBreakerRejection)
        }

      override def state: F[CircuitState] = ref.get
    }
}
