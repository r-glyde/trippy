package com.glyde

import cats.effect.concurrent.Ref
import cats.syntax.flatMap._
import cats.FlatMap

package object trippy {

  implicit class RefOps[F[_]](val ref: Ref[F, CircuitState]) extends AnyVal {
    def modifyAndSideEffect[A](newState: CircuitState, output: F[A], sideEffect: F[Unit])(implicit F: FlatMap[F]): F[A] =
      sideEffect >> ref.modify(_ => (newState, output)).flatten
  }

}
