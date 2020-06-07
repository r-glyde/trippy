package com.glyde.trippy

import java.util.concurrent.TimeUnit.MILLISECONDS

import cats.effect.{Clock, Concurrent, Sync, Timer}
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

sealed abstract class CircuitBreaker[F[_]](private val ref: Ref[F, CircuitState]) {
  def execute[A](task: F[A]): F[A]
  def state: F[CircuitState] = ref.get
}

object CircuitBreaker {

  import com.glyde.trippy.CircuitBreakerError._

  /**
    * Build a [[CircuitBreaker]] instance wrapped in synchronous effect `F`
    *
    * @param maxFailures failures before tripping switch and setting state to [[Open]]
    * @param resetTimeout [[FiniteDuration]] after which to transition to [[HalfOpen]] state
    * @param callTimeout [[FiniteDuration]] for task completion after which success will count as failure
    */
  def ofSync[F[_] : Sync : Clock](
      maxFailures: Int,
      resetTimeout: FiniteDuration,
      callTimeout: FiniteDuration
  ): F[CircuitBreaker[F]] =
    Ref.of[F, CircuitState](Closed(0)).map { ref =>
      new CircuitBreaker[F](ref) {
        override def execute[A](task: F[A]): F[A] =
          ref.modify {
            case c: Closed => (c, attemptTask(task, c.failures))
            case o: Open =>
              (o, attemptFromOpen(o.openedAt, resetTimeout.toMillis, attemptTask(task, maxFailures), ref))
            case HalfOpen => (HalfOpen, Sync[F].raiseError[A](CircuitBreakerRejection))
          }.flatten

        def attemptTask[A](task: F[A], failureCount: Int): F[A] =
          for {
            start  <- Clock[F].realTime(MILLISECONDS)
            result <- task.attempt
            now    <- Clock[F].realTime(MILLISECONDS)
            out <- result match {
                    case Right(a) =>
                      val newCount = if ((now - start) >= callTimeout.toMillis) failureCount + 1 else 0
                      val newState = if (newCount >= maxFailures) Open(now) else Closed(newCount)
                      ref.modify(_ => (newState, Sync[F].unit)).map(_ => a)
                    case Left(e) =>
                      val newState = if ((failureCount + 1) >= maxFailures) Open(now) else Closed(failureCount + 1)
                      ref.modify(_ => (newState, Sync[F].raiseError[A](e))).flatten
                  }
          } yield out
      }
    }

  /**
    * Build a [[CircuitBreaker]] instance wrapped in concurrent effect `F`
    *
    * @param maxFailures failures before tripping switch and setting state to [[Open]]
    * @param resetTimeout [[FiniteDuration]] after which to transition to [[HalfOpen]] state
    * @param callTimeout [[FiniteDuration]] for task completion after which it short circuits with failure
    */
  def ofConc[F[_] : Concurrent : Timer](
      maxFailures: Int,
      resetTimeout: FiniteDuration,
      callTimeout: FiniteDuration
  ): F[CircuitBreaker[F]] =
    Ref.of[F, CircuitState](Closed(0)).map { ref =>
      new CircuitBreaker[F](ref) {
        override def execute[A](task: F[A]): F[A] =
          ref.modify {
            case c: Closed => (c, attemptTask(task, c.failures))
            case o: Open =>
              (o, attemptFromOpen(o.openedAt, resetTimeout.toMillis, attemptTask(task, maxFailures), ref))
            case HalfOpen => (HalfOpen, Sync[F].raiseError[A](CircuitBreakerRejection))
          }.flatten

        def attemptTask[A](task: F[A], failureCount: Int): F[A] = {
          val timeoutTask = Timer[F].sleep(callTimeout) >> Sync[F].raiseError[A](CircuitBreakerTimeout).attempt
          for {
            result <- Concurrent[F].race(timeoutTask, task.attempt)
            now    <- Clock[F].realTime(MILLISECONDS)
            out <- result match {
                    case Left(_) =>
                      val newState = if ((failureCount + 1) >= maxFailures) Open(now) else Closed(failureCount + 1)
                      ref.modify(_ => (newState, Sync[F].raiseError[A](CircuitBreakerTimeout))).flatten
                    case Right(Right(a)) => ref.modify(_ => (Closed(0), Sync[F].unit)).map(_ => a)
                    case Right(Left(e)) =>
                      val newState = if ((failureCount + 1) >= maxFailures) Open(now) else Closed(failureCount + 1)
                      ref.modify(_ => (newState, Sync[F].raiseError[A](e))).flatten
                  }
          } yield out
        }
      }
    }

  private def attemptFromOpen[F[_] : Sync : Clock, A](
      openedAt: Long,
      resetMillis: Long,
      task: F[A],
      ref: Ref[F, CircuitState]
  ): F[A] =
    Clock[F].realTime(MILLISECONDS).flatMap { now =>
      ref.modify { _ =>
        if ((now - openedAt) >= resetMillis) (HalfOpen, task)
        else (Open(openedAt), Sync[F].raiseError[A](CircuitBreakerRejection))
      }.flatten
    }
}
