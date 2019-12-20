package arrow.test.laws

import arrow.Kind
import arrow.core.extensions.eq
import arrow.test.generators.GenK
import arrow.test.generators.applicative
import arrow.test.generators.functionAToB
import arrow.test.generators.intSmall
import arrow.typeclasses.Applicative
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll

object ApplicativeLaws {

  fun <F> laws(A: Applicative<F>, GENK: GenK<F>, EQK: EqK<F>): List<Law> =
    FunctorLaws.laws(A, GENK, EQK) + applicativeLaws(A, EQK)

  @Deprecated("use the other laws function that provides GenK/EqK params instead of Gen/cf https://github.com/arrow-kt/arrow/issues/1819")
  internal fun <F> laws(A: Applicative<F>, EQK: EqK<F>): List<Law> =
    FunctorLaws.laws(A, Gen.int().map { A.just(it) }, EQK) + applicativeLaws(A, EQK)

  private fun <F> applicativeLaws(A: Applicative<F>, EQK: EqK<F>): List<Law> {
    val EQ = EQK.liftEq(Int.eq())

    return listOf(
      Law("Applicative Laws: ap identity") { A.apIdentity(EQ) },
      Law("Applicative Laws: homomorphism") { A.homomorphism(EQ) },
      Law("Applicative Laws: interchange") { A.interchange(EQ) },
      Law("Applicative Laws: map derived") { A.mapDerived(EQ) },
      Law("Applicative Laws: cartesian builder map") { A.cartesianBuilderMap(EQ) },
      Law("Applicative Laws: cartesian builder tupled") { A.cartesianBuilderTupled(EQ) }
    )
  }

  fun <F> Applicative<F>.apIdentity(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().applicative(this)) { fa: Kind<F, Int> ->
      fa.ap(just { n: Int -> n }).equalUnderTheLaw(fa, EQ)
    }

  fun <F> Applicative<F>.homomorphism(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.functionAToB<Int, Int>(Gen.int()), Gen.int()) { ab: (Int) -> Int, a: Int ->
      just(a).ap(just(ab)).equalUnderTheLaw(just(ab(a)), EQ)
    }

  fun <F> Applicative<F>.interchange(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.functionAToB<Int, Int>(Gen.int()).applicative(this), Gen.int()) { fa: Kind<F, (Int) -> Int>, a: Int ->
      just(a).ap(fa).equalUnderTheLaw(fa.ap(just { x: (Int) -> Int -> x(a) }), EQ)
    }

  fun <F> Applicative<F>.mapDerived(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().applicative(this), Gen.functionAToB<Int, Int>(Gen.int())) { fa: Kind<F, Int>, f: (Int) -> Int ->
      fa.map(f).equalUnderTheLaw(fa.ap(just(f)), EQ)
    }

  fun <F> Applicative<F>.cartesianBuilderMap(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.intSmall(), Gen.intSmall(), Gen.intSmall(), Gen.intSmall(), Gen.intSmall(), Gen.intSmall()) { a: Int, b: Int, c: Int, d: Int, e: Int, f: Int ->
      map(just(a), just(b), just(c), just(d), just(e), just(f)) { (x, y, z, u, v, w) -> x + y + z - u - v - w }.equalUnderTheLaw(just(a + b + c - d - e - f), EQ)
    }

  fun <F> Applicative<F>.cartesianBuilderTupled(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.intSmall(), Gen.intSmall(), Gen.intSmall(), Gen.intSmall(), Gen.intSmall(), Gen.intSmall()) { a: Int, b: Int, c: Int, d: Int, e: Int, f: Int ->
      tupled(just(a), just(b), just(c), just(d), just(e), just(f)).map { (x, y, z, u, v, w) -> x + y + z - u - v - w }.equalUnderTheLaw(just(a + b + c - d - e - f), EQ)
    }
}
