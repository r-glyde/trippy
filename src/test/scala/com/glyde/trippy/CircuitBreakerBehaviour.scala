package com.glyde.trippy

import base.IOSpecBase
import cats.effect.IO
import cats.implicits._
import com.glyde.trippy.CircuitBreakerError._
import org.scalatest.EitherValues
import org.scalatest.Suite
import org.scalatest.matchers.should.Matchers

import scala.annotation.nowarn
import scala.concurrent.duration._

@nowarn("msg=right-biased")
trait CircuitBreakerBehaviour { this: IOSpecBase with Matchers with Suite with EitherValues =>

  val successIO = IO(42)
  val failedIO  = IO[Int](throw new RuntimeException("Boom"))

  def circuitBreaker(createBreaker: (Int, FiniteDuration, FiniteDuration) => IO[CircuitBreaker[IO]]) = {
    "execute a task when run in closed state" in {
      for {
        breaker <- createBreaker(1, 10.seconds, 10.seconds)
        output  <- breaker.execute(successIO).attempt
      } yield output.right.value shouldBe 42
    }

    "increment failure count on task failure" in {
      for {
        breaker <- createBreaker(2, 10.seconds, 10.seconds)
        fail    <- breaker.execute(failedIO).attempt
        state   <- breaker.state
        output  <- breaker.execute(successIO).attempt
      } yield {
        fail.left.value.getMessage shouldBe "Boom"
        state shouldBe Closed(1)
        output.right.value shouldBe 42
      }
    }

    "fail fast a task after reaching maxFailures for failed tasks" in {
      for {
        breaker <- createBreaker(1, 10.seconds, 10.seconds)
        failA   <- breaker.execute(failedIO).attempt
        state   <- breaker.state
        failB   <- breaker.execute(successIO).attempt
      } yield {
        failA.left.value.getMessage shouldBe "Boom"
        state shouldBe a[Open]
        failB.left.value shouldBe CircuitBreakerRejection
      }
    }

    "execute a task after the configured resetTimeout" in {
      for {
        breaker <- createBreaker(1, 1.millis, 10.seconds)
        _       <- breaker.execute(failedIO).attempt
        _       <- IO.sleep(100.millis)
        output  <- breaker.execute(successIO).attempt
        state   <- breaker.state
      } yield {
        output.right.value shouldBe 42
        state shouldBe Closed(0)
      }
    }

    "set state back to open if attemped task fails after the resetTimeout" in {
      for {
        breaker <- createBreaker(1, 1.millis, 10.seconds)
        _       <- breaker.execute(failedIO).attempt
        _       <- IO.sleep(100.millis)
        fail    <- breaker.execute(failedIO).attempt
        state   <- breaker.state
      } yield {
        fail.left.value.getMessage shouldBe "Boom"
        state shouldBe a[Open]
      }
    }
  }
}
