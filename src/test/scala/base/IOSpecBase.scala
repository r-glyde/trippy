package base

import cats.effect.{ContextShift, IO, Timer}
import org.scalatest.Assertion
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.concurrent.{ExecutionContext, Future}

trait IOSpecBase extends AsyncWordSpecLike {
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO]               = IO.timer(executionContext)

  implicit def ioToFutureAssertion(io: IO[Assertion]): Future[Assertion] = io.unsafeToFuture
}
