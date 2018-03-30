package arrow.test.laws

import arrow.Kind
import arrow.core.None
import arrow.core.Some
import arrow.mtl.typeclasses.TraverseFilter
import arrow.test.generators.genApplicative
import arrow.test.generators.genFunctionAToB
import arrow.test.generators.genIntSmall
import arrow.typeclasses.Applicative
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll

object TraverseFilterLaws {

    //FIXME(paco): TraverseLaws cannot receive AP::just due to a crash caused by the inliner. Check in TraverseLaws why.
    inline fun <F> laws(TF: TraverseFilter<F>, GA: Applicative<F>, noinline cf: (Int) -> Kind<F, Int>, EQ: Eq<Kind<F, Int>>, EQ_NESTED: Eq<Kind<F, Kind<F, Int>>> = Eq.any()): List<Law> =
            TraverseLaws.laws(TF, GA, cf, EQ) + listOf(
                    Law("TraverseFilter Laws: Identity", { TF.identityTraverseFilter(GA, EQ_NESTED) }),
                    Law("TraverseFilter Laws: filterA consistent with TraverseFilter", { TF.filterAconsistentWithTraverseFilter(GA, EQ_NESTED) })
            )

    fun <F> TraverseFilter<F>.identityTraverseFilter(GA: Applicative<F>, EQ: Eq<Kind<F, Kind<F, Int>>> = Eq.any()) =
            forAll(genApplicative(genIntSmall(), GA), { fa: Kind<F, Int> ->
                fa.traverseFilter(GA, { GA.just(Some(it)) }).equalUnderTheLaw(GA.just(fa), EQ)
            })

    fun <F> TraverseFilter<F>.filterAconsistentWithTraverseFilter(GA: Applicative<F>, EQ: Eq<Kind<F, Kind<F, Int>>> = Eq.any()) =
            forAll(genApplicative(genIntSmall(), GA), genFunctionAToB(genApplicative(Gen.bool(), GA)), { fa: Kind<F, Int>, f: (Int) -> Kind<F, Boolean> ->
                fa.filterA(f, GA).equalUnderTheLaw(fa.traverseFilter(GA, { a -> f(a).map { b: Boolean -> if (b) Some(a) else None } }), EQ)
            })
}
