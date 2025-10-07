package io.bazel.kotlin.plugin.jdeps.k2.checker.declaration

import io.bazel.kotlin.plugin.jdeps.k2.ClassUsageRecorder
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassLikeChecker
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.resolve.getSuperTypes

internal class ClassLikeChecker(
  private val classUsageRecorder: ClassUsageRecorder,
) : FirClassLikeChecker(MppCheckerKind.Common) {
  context(CheckerContext, DiagnosticReporter)
  override fun check(
    declaration: FirClassLikeDeclaration,
  ) {
    declaration.symbol.let { classUsageRecorder.recordClass(it, this@CheckerContext) }
    // [recordClass] also handles supertypes, but this marks direct supertypes as explicit
    declaration.symbol.getSuperTypes(this@CheckerContext.session, recursive = false).forEach {
      classUsageRecorder.recordConeType(it, this@CheckerContext)
    }
  }
}
