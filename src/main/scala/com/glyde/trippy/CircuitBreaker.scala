package com.glyde.trippy

import java.util.concurrent.TimeUnit.MILLISECONDS

import cats.effect.{Clock, Concurrent, Sync, Timer}
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

sealed abstract class CircuitBreaker[F[_] : Sync](
    private val ref: Ref[F, CircuitState],
    private val whenClosed: Option[F[Unit]],
    private val whenOpened: Option[F[Unit]],
    private val whenHalfOpened: Option[F[Unit]]
) {
  def execute[A](task: F[A]): F[A]

  val state: F[CircuitState] = ref.get
  val onClose: F[Unit]       = whenClosed.getOrElse(Sync[F].unit)
  val onOpen: F[Unit]        = whenOpened.getOrElse(Sync[F].unit)
  val onHalfOpen: F[Unit]    = whenHalfOpened.getOrElse(Sync[F].unit)

  private[trippy] def sideTask(oldState: CircuitState, newState: CircuitState): F[Unit] = (oldState, newState) match {
    case (HalfOpen | _: Open, _: Closed) => onClose
    case (_: Closed | _: Open, HalfOpen) => onHalfOpen
    case (_: Closed | HalfOpen, _: Open) => onOpen
    case _                               => Sync[F].unit
  }
}

object CircuitBreaker {

  import com.glyde.trippy.CircuitBreakerError._

  /**
    * Build a [[CircuitBreaker]] instance wrapped in synchronous effect `F`
    *
    * @param maxFailures failures before tripping switch and setting state to [[Open]]
    * @param callTimeout [[FiniteDuration]] for task completion after which success will count as failure
    * @param resetTimeout [[FiniteDuration]] after which to transition to [[HalfOpen]] state
    */
  def sync[F[_] : Sync : Clock](
      maxFailures: Int,
      callTimeout: FiniteDuration,
      resetTimeout: FiniteDuration,
      resetBackoff: FiniteDuration => FiniteDuration = identity,
      whenClosed: Option[F[Unit]] = None,
      whenOpened: Option[F[Unit]] = None,
      whenHalfOpened: Option[F[Unit]] = None
  ): F[CircuitBreaker[F]] =
    Ref.of[F, CircuitState](Closed(0)).map { ref =>
      new CircuitBreaker[F](ref, whenClosed, whenOpened, whenHalfOpened) {
        override def execute[A](task: F[A]): F[A] =
          ref.modify {
            case c: Closed => (c, attemptTask(task, c.failures, resetTimeout))
            case o: Open =>
              (o, attemptFromOpen(o, attemptTask(task, maxFailures, resetBackoff(o.resetAfter)), onHalfOpen, ref))
            case HalfOpen => (HalfOpen, Sync[F].raiseError[A](CircuitBreakerRejection))
          }.flatten

        def attemptTask[A](task: F[A], failureCount: Int, resetAfter: FiniteDuration): F[A] =
          for {
            start        <- Clock[F].realTime(MILLISECONDS)
            result       <- task.attempt
            now          <- Clock[F].realTime(MILLISECONDS)
            currentState <- ref.get
            out <- result match {
                    case Right(a) =>
                      val newCount = if ((now - start) >= callTimeout.toMillis) failureCount + 1 else 0
                      val newState = if (newCount >= maxFailures) Open(now, resetAfter) else Closed(newCount)
                      ref.modifyAndSideEffect(newState, Sync[F].delay(a), sideTask(currentState, newState))
                    case Left(e) =>
                      val newState =
                        if ((failureCount + 1) >= maxFailures) Open(now, resetAfter) else Closed(failureCount + 1)
                      ref.modifyAndSideEffect(newState, Sync[F].raiseError[A](e), sideTask(currentState, newState))
                  }
          } yield out
      }
    }

  /**
    * Build a [[CircuitBreaker]] instance wrapped in concurrent effect `F`
    *
    * @param maxFailures failures before tripping switch and setting state to [[Open]]
    * @param callTimeout [[FiniteDuration]] for task completion after which it short circuits with failure
    * @param resetTimeout [[FiniteDuration]] after which to transition to [[HalfOpen]] state
    */
  def concurrent[F[_] : Concurrent : Timer](
      maxFailures: Int,
      callTimeout: FiniteDuration,
      resetTimeout: FiniteDuration,
      resetBackoff: FiniteDuration => FiniteDuration = identity,
      whenClosed: Option[F[Unit]] = None,
      whenOpened: Option[F[Unit]] = None,
      whenHalfOpened: Option[F[Unit]] = None
  ): F[CircuitBreaker[F]] =
    Ref.of[F, CircuitState](Closed(0)).map { ref =>
      new CircuitBreaker[F](ref, whenClosed, whenOpened, whenHalfOpened) {
        override def execute[A](task: F[A]): F[A] =
          ref.modify {
            case c: Closed => (c, attemptTask(task, c.failures, resetTimeout))
            case o: Open =>
              (o, attemptFromOpen(o, attemptTask(task, maxFailures, resetBackoff(o.resetAfter)), onHalfOpen, ref))
            case HalfOpen => (HalfOpen, Sync[F].raiseError[A](CircuitBreakerRejection))
          }.flatten

        def attemptTask[A](task: F[A], failureCount: Int, resetAfter: FiniteDuration): F[A] = {
          val raiseTimeout = Sync[F].raiseError[A](CircuitBreakerTimeout)
          val timeoutTask  = Timer[F].sleep(callTimeout) >> raiseTimeout.attempt
          for {
            result       <- Concurrent[F].race(timeoutTask, task.attempt)
            now          <- Clock[F].realTime(MILLISECONDS)
            currentState <- ref.get
            out <- result match {
                    case Left(_) =>
                      val newState =
                        if ((failureCount + 1) >= maxFailures) Open(now, resetAfter) else Closed(failureCount + 1)
                      ref.modifyAndSideEffect(newState, raiseTimeout, sideTask(currentState, newState))
                    case Right(Right(a)) =>
                      ref.modifyAndSideEffect(Closed(0), Sync[F].delay(a), sideTask(currentState, Closed(0)))
                    case Right(Left(e)) =>
                      val newState =
                        if ((failureCount + 1) >= maxFailures) Open(now, resetAfter) else Closed(failureCount + 1)
                      ref.modifyAndSideEffect(newState, Sync[F].raiseError[A](e), sideTask(currentState, newState))
                  }
          } yield out
        }
      }
    }

  private def attemptFromOpen[F[_] : Sync : Clock, A](
      open: Open,
      task: F[A],
      onHalfOpen: F[Unit],
      ref: Ref[F, CircuitState]
  ): F[A] =
    Clock[F].realTime(MILLISECONDS).flatMap { now =>
      ref.modify { _ =>
        if ((now - open.openedAt) >= open.resetAfter.toMillis) (HalfOpen, onHalfOpen >> task)
        else (open, Sync[F].raiseError[A](CircuitBreakerRejection))
      }.flatten
    }
}
