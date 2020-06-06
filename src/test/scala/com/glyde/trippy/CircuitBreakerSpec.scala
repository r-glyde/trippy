package com.glyde.trippy

import base.IOSpecBase
import cats.effect.IO
import cats.implicits._
import org.scalatest.Suite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import org.scalatest.EitherValues

class CircuitBreakerSpec extends IOSpecBase with Matchers with Suite with EitherValues {

  val successIO = IO(())
  val failedIO  = IO.fromEither((new RuntimeException("Boom")).asLeft[Unit])

  "CircuitBreaker" should {

    "execute a task when run in closed state" in {
      CircuitBreaker
        .make[IO](1, 10.seconds)
        .flatMap(_.execute(successIO).attempt)
        .map(_ shouldBe Symbol("right"))
    }

    "increment failure count on task failure" in {
      CircuitBreaker
        .make[IO](2, 10.seconds)
        .flatMap(breaker => breaker.execute(failedIO).attempt *> breaker.state)
        .map(_ shouldBe Closed(1))
    }

    "fail a task after reaching maxFailures" in {
      for {
        breaker <- CircuitBreaker.make[IO](1, 10.seconds)
        failA   <- breaker.execute(failedIO).attempt
        failB   <- breaker.execute(successIO).attempt
        state   <- breaker.state
      } yield {
        failA.left.value.getMessage shouldBe "Boom"
        failB.left.value shouldBe CircuitBreakerRejection
        state shouldBe a[Open]
      }
    }

    "succeed a task after the configured resetTimeout" in {
      for {
        breaker <- CircuitBreaker.make[IO](1, 1.millis)
        _       <- breaker.execute(failedIO).attempt
        _       <- IO.sleep(100.millis)
        success <- breaker.execute(successIO).attempt
        state   <- breaker.state
      } yield {
        success shouldBe Symbol("right")
        state shouldBe Closed(0)
      }
    }

    "set state back to open if attemped task fails after the resetTimeout" in {
      for {
        breaker <- CircuitBreaker.make[IO](1, 1.millis)
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
