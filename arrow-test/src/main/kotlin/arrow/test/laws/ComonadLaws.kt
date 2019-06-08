package arrow.test.laws

import arrow.Kind
import arrow.data.Cokleisli
import arrow.test.generators.functionAToB
import arrow.typeclasses.Comonad
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll

object ComonadLaws {

  fun <F> laws(CM: Comonad<F>, cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): List<Law> =
    FunctorLaws.laws(CM, cf, EQ) + listOf(
      Law("Comonad Laws: duplicate then extract is identity") { CM.duplicateThenExtractIsId(cf, EQ) },
      Law("Comonad Laws: duplicate then map into extract is identity") { CM.duplicateThenMapExtractIsId(cf, EQ) },
      Law("Comonad Laws: map and coflatMap are coherent") { CM.mapAndCoflatmapCoherence(cf, EQ) },
      Law("Comonad Laws: left identity") { CM.comonadLeftIdentity(cf, EQ) },
      Law("Comonad Laws: right identity") { CM.comonadRightIdentity(cf, EQ) },
      Law("Comonad Laws: cokleisli left identity") { CM.cokleisliLeftIdentity(cf, EQ) },
      Law("Comonad Laws: cokleisli right identity") { CM.cokleisliRightIdentity(cf, EQ) },
      Law("Comonad Laws: cobinding") { CM.cobinding(cf, EQ) }
    )

  fun <F> Comonad<F>.duplicateThenExtractIsId(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().map(cf)) { fa: Kind<F, Int> ->
      fa.duplicate().extract().equalUnderTheLaw(fa, EQ)
    }

  fun <F> Comonad<F>.duplicateThenMapExtractIsId(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().map(cf)) { fa: Kind<F, Int> ->
      fa.duplicate().map { it.extract() }.equalUnderTheLaw(fa, EQ)
    }

  fun <F> Comonad<F>.mapAndCoflatmapCoherence(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().map(cf), Gen.functionAToB<Int, Int>(Gen.int())) { fa: Kind<F, Int>, f: (Int) -> Int ->
      fa.map(f).equalUnderTheLaw(fa.coflatMap { f(it.extract()) }, EQ)
    }

  fun <F> Comonad<F>.comonadLeftIdentity(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().map(cf)) { fa: Kind<F, Int> ->
      fa.coflatMap { it.extract() }.equalUnderTheLaw(fa, EQ)
    }

  fun <F> Comonad<F>.comonadRightIdentity(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().map(cf), Gen.functionAToB<Kind<F, Int>, Kind<F, Int>>(Gen.int().map(cf))) { fa: Kind<F, Int>, f: (Kind<F, Int>) -> Kind<F, Int> ->
      fa.coflatMap(f).extract().equalUnderTheLaw(f(fa), EQ)
    }

  fun <F> Comonad<F>.cokleisliLeftIdentity(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>) {
    val MM = this
    forAll(Gen.int().map(cf), Gen.functionAToB<Kind<F, Int>, Kind<F, Int>>(Gen.int().map(cf))) { fa: Kind<F, Int>, f: (Kind<F, Int>) -> Kind<F, Int> ->
      Cokleisli(MM) { hk: Kind<F, Int> -> hk.extract() }.andThen(Cokleisli(MM, f)).run(fa).equalUnderTheLaw(f(fa), EQ)
    }
  }

  fun <F> Comonad<F>.cokleisliRightIdentity(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>) {
    val MM = this
    forAll(Gen.int().map(cf), Gen.functionAToB<Kind<F, Int>, Kind<F, Int>>(Gen.int().map(cf))) { fa: Kind<F, Int>, f: (Kind<F, Int>) -> Kind<F, Int> ->
      Cokleisli(MM, f).andThen(Cokleisli(MM) { hk: Kind<F, Kind<F, Int>> -> hk.extract() }).run(fa).equalUnderTheLaw(f(fa), EQ)
    }
  }

  fun <F> Comonad<F>.cobinding(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().map(cf)) { fa: Kind<F, Int> ->
      fx.comonad {
        val x = fa.extract()
        val y = extract { fa.map { it + x } }
        fa.map { x + y }
      }.equalUnderTheLaw(fa.map { it * 3 }, EQ)
    }
}
