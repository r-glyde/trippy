package trippy

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

class CircuitBreakerSpec extends AsyncWordSpec, AsyncIOSpec, Matchers, EitherValues {

  val success: IO[String] = IO("cupcat")
  val failure: IO[String] = IO.raiseError(Throwable("boom!"))
  val slow: IO[String] = IO.sleep(50.millis) >> success

  "CircuitBreaker#protect" should {
    "succeed with the result of a given task if breaker is closed" in {
      CircuitBreaker.make[IO](2, 1.second, 10.millis).flatMap { cb =>
          for {
            out <- cb.protect(success)
            state <- cb.state
          } yield (out, state)
        }
        .map(_ shouldBe("cupcat", CircuitState.Closed(0)))
    }

    "fail with the error of a given task if breaker is closed" in {
      CircuitBreaker.make[IO](2, 1.second, 10.millis).flatMap { cb =>
          for {
            out <- cb.protect(failure).attempt
            state <- cb.state
          } yield (out.left.map(_.getMessage), state)
        }
        .map(_ shouldBe(Left("boom!"), CircuitState.Closed(1)))
    }

    "reset the failure counter after a successful task" in {
      val cb = CircuitBreaker.of(Ref.unsafe[IO, CircuitState](CircuitState.Closed(1)), 2, 1.second, 10.millis)

      cb.protect(success) >> cb.state.map(_ shouldBe CircuitState.Closed(0))
    }

    "open the circuit breaker when failure count reaches max failures" in {
      val cb = CircuitBreaker.of(Ref.unsafe[IO, CircuitState](CircuitState.Closed(1)), 2, 1.second, 10.millis)

      cb.protect(failure).attempt >> cb.state.map(_ shouldBe a[CircuitState.Open])
    }

    "reject a task if breaker has been opened due to too many failures" in {
      val cb = CircuitBreaker.of(Ref.unsafe[IO, CircuitState](CircuitState.Open(Instant.now().plusSeconds(1))), 2, 1.second, 10.millis)

      cb.protect(success).attempt.map(_ shouldBe Left(CircuitBreakerRejection))
    }

    "reset an open circuit breaker after the reset timeout has passed" in {
      val cb = CircuitBreaker.of(Ref.unsafe[IO, CircuitState](CircuitState.Open(Instant.now().minusSeconds(1))), 2, 1.second, 10.millis)

      cb.protect(success).map(_ shouldBe "cupcat")
    }

    "time out a slow task" in {
      val cb = CircuitBreaker.of(Ref.unsafe[IO, CircuitState](CircuitState.Closed(0)), 2, 1.second, 10.millis)

      cb.protect(slow).attempt.map(_.left.value shouldBe a[TimeoutException])
    }

    "time out a slow task when closing" in {
      val cb = CircuitBreaker.of(Ref.unsafe[IO, CircuitState](CircuitState.Open(Instant.now().minusSeconds(1))), 2, 1.second, 10.millis)

      cb.protect(slow).attempt.map(_.left.value shouldBe a[TimeoutException])
    }
  }
}
