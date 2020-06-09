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
class CircuitBreakerSpec extends IOSpecBase with Matchers with Suite with EitherValues with CircuitBreakerBehaviour {

  "A synchronous circuit breaker" should {
    behave like circuitBreaker(CircuitBreaker.sync[IO])

    "increment failure count when task exceeds callTimeout" in {
      for {
        breaker <- CircuitBreaker.sync[IO](2, 10.seconds, 1.millis)
        slow    <- breaker.execute(IO.sleep(250.millis) >> successIO).attempt
        state   <- breaker.state
        output  <- breaker.execute(successIO).attempt
      } yield {
        slow.right.value shouldBe 42
        state shouldBe Closed(1)
        output.right.value shouldBe 42
      }
    }

    "fail fast a task after reaching maxFailures from call timeouts" in {
      for {
        breaker <- CircuitBreaker.sync[IO](1, 10.seconds, 1.millis)
        slow    <- breaker.execute(IO.sleep(250.millis) >> successIO).attempt
        state   <- breaker.state
        fail    <- breaker.execute(successIO).attempt
      } yield {
        slow.right.value shouldBe 42
        state shouldBe a[Open]
        fail.left.value shouldBe CircuitBreakerRejection
      }
    }
  }

  "A concurrent circuit breaker" should {
    behave like circuitBreaker(CircuitBreaker.concurrent[IO])

    "short circuit task execution exceeding callTimeout" in {
      for {
        breaker <- CircuitBreaker.concurrent[IO](1, 10.seconds, 1.millis)
        slow    <- breaker.execute(IO.sleep(250.millis) >> successIO).attempt
        state   <- breaker.state
      } yield {
        slow.left.value shouldBe CircuitBreakerTimeout
        state shouldBe a[Open]
      }
    }

    "fail fast a task after reaching maxFailures from call timeouts" in {
      for {
        breaker <- CircuitBreaker.concurrent[IO](1, 10.seconds, 1.millis)
        slow    <- breaker.execute(IO.sleep(250.millis) >> successIO).attempt
        state   <- breaker.state
        fail    <- breaker.execute(successIO).attempt
      } yield {
        slow.left.value shouldBe CircuitBreakerTimeout
        state shouldBe a[Open]
        fail.left.value shouldBe CircuitBreakerRejection
      }
    }

  }

}
