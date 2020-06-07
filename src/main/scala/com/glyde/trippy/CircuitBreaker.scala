package com.glyde.trippy

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

trait CircuitBreaker[F[_]] {
  def execute[A](task: F[A]): F[A]
  def state: F[CircuitState]
}

object CircuitBreaker {
  // add a call timeout
  // make resetTimeout: FiniteDuration => FiniteDuration
  def of[F[_] : Sync : Clock](
      maxFailures: Int,
      resetTimeout: FiniteDuration
  ): F[CircuitBreaker[F]] = Ref.of[F, CircuitState](Closed(0)).map { ref =>
    new CircuitBreaker[F] {
      override def state: F[CircuitState] = ref.get

      override def execute[A](task: F[A]): F[A] =
        ref.modify {
          case c: Closed => (c, attemptFromClosed(task, c.failures + 1))
          case o: Open   => (o, attemptFromOpen(task, o.openedAt))
          case HalfOpen  => (HalfOpen, Sync[F].raiseError[A](CircuitBreakerRejection))
        }.flatten

      def attemptFromClosed[A](task: F[A], count: Int): F[A] = task.attempt.flatMap {
        case Right(a) => ref.modify(_ => (Closed(0), Sync[F].unit)).map(_ => a)
        case Left(e) =>
          Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { now =>
            ref.modify { _ =>
              val newState = if (count >= maxFailures) Open(now) else Closed(count)
              (newState, Sync[F].raiseError[A](e))
            }.flatten
          }
      }

      def attemptFromOpen[A](task: F[A], openedAt: Long): F[A] =
        Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { now =>
          ref.modify { _ =>
            if ((now - openedAt) >= resetTimeout.toMillis) (HalfOpen, attemptFromClosed(task, maxFailures))
            else (Open(openedAt), Sync[F].raiseError[A](CircuitBreakerRejection))
          }.flatten
        }
    }
  }
}
