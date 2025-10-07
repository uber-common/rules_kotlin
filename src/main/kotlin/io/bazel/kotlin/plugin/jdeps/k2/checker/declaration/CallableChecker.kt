package io.bazel.kotlin.plugin.jdeps.k2.checker.declaration

import io.bazel.kotlin.plugin.jdeps.k2.ClassUsageRecorder
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.isExtension

internal class CallableChecker(
  private val classUsageRecorder: ClassUsageRecorder,
) : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  /**
   * Tracks the return type & type parameters of a callable declaration. Function parameters are
   * tracked in [FunctionChecker].
   */
  context(CheckerContext, DiagnosticReporter)
  override fun check(
    declaration: FirCallableDeclaration,
  ) {
    // return type
    declaration.returnTypeRef.let { classUsageRecorder.recordTypeRef(it, this@CheckerContext) }

    // type params
    declaration.typeParameters.forEach { typeParam ->
      typeParam.symbol.resolvedBounds.forEach { typeParamBound ->
        typeParamBound.let { classUsageRecorder.recordTypeRef(it, this@CheckerContext) }
      }
    }

    // receiver param for extensions
    if (declaration !is FirAnonymousFunction) {
      declaration.receiverParameter?.typeRef?.let {
        classUsageRecorder.recordTypeRef(it, this@CheckerContext, isExplicit = declaration.isExtension)
      }
    }
  }
}
