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
class CircuitBreakerSpec extends IOSpecBase with Matchers with Suite with EitherValues with CircuitBreakerBehaviour {

  "A synchronous circuit breaker" should {
    behave like circuitBreaker(CircuitBreaker.ofSync[IO])

    "increment failure count when task exceeds callTimeout" in {
      for {
        breaker <- CircuitBreaker.ofSync[IO](2, 10.seconds, 1.millis)
        slow    <- breaker.execute(IO.sleep(100.millis) >> successIO).attempt
        state   <- breaker.state
        output  <- breaker.execute(successIO).attempt
      } yield {
        slow.right.value shouldBe 42
        state shouldBe Closed(1)
        output.right.value shouldBe 42
      }
    }
  }

}
