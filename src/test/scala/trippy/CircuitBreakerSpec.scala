package trippy

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Mutex
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
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
      CircuitBreaker.nonLocking[IO](2, 1.second, 10.millis).flatMap { cb =>
          for {
            out <- cb.protect(success)
            state <- cb.state
          } yield (out, state)
        }
        .map(_ shouldBe("cupcat", CircuitState.Closed(0)))
    }

    "fail with the error of a given task if breaker is closed" in {
      CircuitBreaker.nonLocking[IO](2, 1.second, 10.millis).flatMap { cb =>
          for {
            out <- cb.protect(failure).attempt
            state <- cb.state
          } yield (out.left.map(_.getMessage), state)
        }
        .map(_ shouldBe(Left("boom!"), CircuitState.Closed(1)))
    }

    "reset the failure counter after a successful task" in {
      buildCircuitBreaker(CircuitState.Closed(1), 2, 1.second, 10.millis).flatMap { cb =>
        cb.protect(success) >> cb.state.map(_ shouldBe CircuitState.Closed(0))
      }
    }

    "open the circuit breaker when failure count reaches max failures" in {
      buildCircuitBreaker(CircuitState.Closed(1), 2, 1.second, 10.millis).flatMap { cb =>
        cb.protect(failure).attempt >> cb.state.map(_ shouldBe a[CircuitState.Open])
      }

    }

    "reject a task if breaker has been opened due to too many failures" in {
      buildCircuitBreaker(CircuitState.Open(Instant.now().plusSeconds(1)), 2, 1.second, 10.millis).flatMap { cb =>
        cb.protect(success).attempt.map(_ shouldBe Left(CircuitBreakerRejection))
      }

    }

    "reset an open circuit breaker after the reset timeout has passed" in {
      buildCircuitBreaker(CircuitState.Open(Instant.now().minusSeconds(1)), 2, 1.second, 10.millis).flatMap { cb =>
        cb.protect(success).map(_ shouldBe "cupcat")
      }

    }

    "time out a slow task" in {
      buildCircuitBreaker(CircuitState.Closed(0), 2, 1.second, 10.millis).flatMap { cb =>
        cb.protect(slow).attempt.map(_.left.value shouldBe a[TimeoutException])
      }

    }

    "time out a slow task when open" in {
      buildCircuitBreaker(CircuitState.Open(Instant.now().minusSeconds(1)), 2, 1.second, 10.millis).flatMap { cb =>
        cb.protect(slow).attempt.map(_.left.value shouldBe a[TimeoutException])
      }

    }

    "only count defined failures" in {
      buildCircuitBreaker(CircuitState.Closed(0), 2, 1.second, 10.millis).flatMap { cb =>
        for {
          out <- cb.protect(failure, PartialFunction.fromFunction(_.isRight)).attempt
          state <- cb.state
        } yield {
          out.left.map(_.getMessage) shouldBe Left("boom!")
          state shouldBe CircuitState.Closed(0)
        }
      }

    }

    "handle being used in parallel" in {
      CircuitBreaker.locking[IO](5, 10.second, 100.millis).flatMap { cb =>
        (1 to 100).toList.parTraverse { i =>
          for {
            out <- cb.protect(failure).attempt
//            _ <- IO.println(s"Task $i [${out.left.value.getMessage}]")
          } yield out
        }.map { result =>
          val failures = result.collect { case Left(error) => error.getMessage }

          failures.length shouldBe 100
          failures.count(_.contains("boom!")) shouldBe 5
          failures.count(!_.contains("boom!")) shouldBe 95
        }
      }

    }
  }

  def buildCircuitBreaker(state: CircuitState, maxFailures: Int, resetTimeout: FiniteDuration, callTimeout: FiniteDuration): IO[CircuitBreaker[IO]] = {
    for {
      ref <- Ref.of[IO, CircuitState](state)
      mtx <- Mutex[IO]
    } yield CircuitBreaker.of[IO](ref, maxFailures, resetTimeout, callTimeout)
  }
}
