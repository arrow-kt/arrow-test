package arrow.test.laws

import arrow.Kind
import arrow.core.Either
import arrow.test.generators.*
import arrow.typeclasses.Eq
import arrow.typeclasses.MonadError
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll

object MonadErrorLaws {

  fun <F> laws(M: MonadError<F, Throwable>, EQERR: Eq<Kind<F, Int>>, EQ_EITHER: Eq<Kind<F, Either<Throwable, Int>>>, EQ: Eq<Kind<F, Int>> = EQERR): List<Law> =
    MonadLaws.laws(M, EQ) + ApplicativeErrorLaws.laws(M, EQERR, EQ_EITHER, EQ) + listOf(
      Law("Monad Error Laws: left zero") { M.monadErrorLeftZero(EQERR) },
      Law("Monad Error Laws: ensure consistency") { M.monadErrorEnsureConsistency(EQERR) }
    )

  fun <F> MonadError<F, Throwable>.monadErrorLeftZero(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.functionAToB<Int, Kind<F, Int>>(Gen.int().applicativeError(this)), Gen.throwable()) { f: (Int) -> Kind<F, Int>, e: Throwable ->
      raiseError<Int>(e).flatMap(f).equalUnderTheLaw(raiseError(e), EQ)
    }

  fun <F> MonadError<F, Throwable>.monadErrorEnsureConsistency(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().applicativeError(this), Gen.throwable(), Gen.functionAToB<Int, Boolean>(Gen.bool())) { fa: Kind<F, Int>, e: Throwable, p: (Int) -> Boolean ->
      fa.ensure({ e }, p).equalUnderTheLaw(fa.flatMap { a -> if (p(a)) just(a) else raiseError(e) }, EQ)
    }
}
