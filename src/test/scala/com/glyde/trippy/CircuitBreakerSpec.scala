package com.glyde.trippy

import base.IOSpecBase
import cats.effect.IO
import cats.implicits._
import org.scalatest.EitherValues
import org.scalatest.Suite
import org.scalatest.matchers.should.Matchers

import scala.annotation.nowarn
import scala.concurrent.duration._

@nowarn("msg=right-biased")
class CircuitBreakerSpec extends IOSpecBase with Matchers with Suite with EitherValues {

  val successIO = IO(())
  val failedIO  = IO.fromEither((new RuntimeException("Boom")).asLeft[Unit])

  "CircuitBreaker" should {

    "execute a task when run in closed state" in {
      for {
        breaker <- CircuitBreaker.of[IO](1, 10.seconds)
        output  <- breaker.execute(successIO).attempt
      } yield output.right.value shouldBe ()
    }

    "increment failure count on task failure" in {
      for {
        breaker <- CircuitBreaker.of[IO](2, 10.seconds)
        fail    <- breaker.execute(failedIO).attempt
        state   <- breaker.state
        output  <- breaker.execute(successIO).attempt
      } yield {
        fail.left.value.getMessage shouldBe "Boom"
        state shouldBe Closed(1)
        output.right.value shouldBe ()
      }
    }

    "fail fast a task after reaching maxFailures" in {
      for {
        breaker <- CircuitBreaker.of[IO](1, 10.seconds)
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
        breaker <- CircuitBreaker.of[IO](1, 1.millis)
        _       <- breaker.execute(failedIO).attempt
        _       <- IO.sleep(100.millis)
        output  <- breaker.execute(successIO).attempt
        state   <- breaker.state
      } yield {
        output.right.value shouldBe ()
        state shouldBe Closed(0)
      }
    }

    "set state back to open if attemped task fails after the resetTimeout" in {
      for {
        breaker <- CircuitBreaker.of[IO](1, 1.millis)
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
