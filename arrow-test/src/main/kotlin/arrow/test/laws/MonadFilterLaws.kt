package arrow.test.laws

import arrow.Kind
import arrow.typeclasses.MonadFilter
import arrow.test.generators.applicative
import arrow.test.generators.functionAToB
import arrow.test.laws.MonadFilterLaws.monadFilterBindWithFilterComprehensions
import arrow.test.laws.MonadFilterLaws.monadFilterConsistency
import arrow.test.laws.MonadFilterLaws.monadFilterEmptyComprehensions
import arrow.test.laws.MonadFilterLaws.monadFilterLeftEmpty
import arrow.test.laws.MonadFilterLaws.monadFilterRightEmpty
import arrow.typeclasses.Apply
import arrow.typeclasses.Eq
import arrow.typeclasses.Functor
import arrow.typeclasses.Selective
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll

object MonadFilterLaws {

  private fun <F> monadFilterLaws(
    MF: MonadFilter<F>,
    cf: (Int) -> Kind<F, Int>,
    EQ: Eq<Kind<F, Int>>
  ): List<Law> = listOf(
      Law("MonadFilter Laws: Left Empty") { MF.monadFilterLeftEmpty(EQ) },
      Law("MonadFilter Laws: Right Empty") { MF.monadFilterRightEmpty(EQ) },
      Law("MonadFilter Laws: Consistency") { MF.monadFilterConsistency(cf, EQ) },
      Law("MonadFilter Laws: Comprehension Guards") { MF.monadFilterEmptyComprehensions(EQ) },
      Law("MonadFilter Laws: Comprehension bindWithFilter Guards") { MF.monadFilterBindWithFilterComprehensions(EQ) }
  )

  fun <F> laws(
    MF: MonadFilter<F>,
    cf: (Int) -> Kind<F, Int>,
    EQ: Eq<Kind<F, Int>>
  ): List<Law> =
    MonadLaws.laws(MF, EQ) +
      FunctorFilterLaws.laws(MF, cf, EQ) +
      monadFilterLaws(MF, cf, EQ)

  fun <F> laws(
    MF: MonadFilter<F>,
    FF: Functor<F>,
    AP: Apply<F>,
    SL: Selective<F>,
    cf: (Int) -> Kind<F, Int>,
    EQ: Eq<Kind<F, Int>>
  ): List<Law> =
    MonadLaws.laws(MF, FF, AP, SL, EQ) +
      FunctorFilterLaws.laws(MF, cf, EQ) +
      monadFilterLaws(MF, cf, EQ)

  fun <F> MonadFilter<F>.monadFilterLeftEmpty(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.functionAToB<Int, Kind<F, Int>>(Gen.int().applicative(this))) { f: (Int) -> Kind<F, Int> ->
      empty<Int>().flatMap(f).equalUnderTheLaw(empty(), EQ)
    }

  fun <F> MonadFilter<F>.monadFilterRightEmpty(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.int().applicative(this)) { fa: Kind<F, Int> ->
      fa.flatMap { empty<Int>() }.equalUnderTheLaw(empty(), EQ)
    }

  fun <F> MonadFilter<F>.monadFilterConsistency(cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.functionAToB<Int, Boolean>(Gen.bool()), Gen.int().map(cf)) { f: (Int) -> Boolean, fa: Kind<F, Int> ->
      fa.filter(f).equalUnderTheLaw(fa.flatMap { a -> if (f(a)) just(a) else empty() }, EQ)
    }

  fun <F> MonadFilter<F>.monadFilterEmptyComprehensions(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.bool(), Gen.int()) { guard: Boolean, n: Int ->
      fx.monadFilter {
        continueIf(guard)
        n
      }.equalUnderTheLaw(if (!guard) empty() else just(n), EQ)
    }

  fun <F> MonadFilter<F>.monadFilterBindWithFilterComprehensions(EQ: Eq<Kind<F, Int>>): Unit =
    forAll(Gen.bool(), Gen.int()) { guard: Boolean, n: Int ->
      fx.monadFilter {
        val x = just(n).bindWithFilter { _ -> guard }
        x
      }.equalUnderTheLaw(if (!guard) empty() else just(n), EQ)
    }
}
