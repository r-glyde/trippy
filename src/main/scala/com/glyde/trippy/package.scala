package com.glyde

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.flatMap._

package object trippy {

  implicit class RefOps[F[_] : Sync, A](val ref: Ref[F, CircuitState]) {
    def modifyAndSideEffect(newState: CircuitState, output: F[A], sideEffect: F[Unit]): F[A] =
      sideEffect >> ref.modify(_ => (newState, output)).flatten
  }

}
